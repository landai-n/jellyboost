package dev.jellyboost.player.syncplay

import dev.jellyboost.player.session.PlayerHandle
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.time.SyncPlayTimeSync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Duration

/**
 * The two timer safety nets under the group's command channel: "the group said go and nothing
 * came" and "the group said stop and nothing came", in both directions.
 *
 * Kept out of [SyncPlayController] because the nets own a disjoint slice
 * of state — their two timer jobs and the coarse [groupPlayingAnchor] — and read the rest of the
 * controller through [Driver]. Everything here runs on the confined single-threaded
 * `@SyncPlayScope` (see `SyncPlayScopeModule`), like the scheduler and the controller: the plain
 * `var`s below are safe because *everything* that touches them runs there.
 *
 * ### The two-window shape
 * Both nets run the same two stages ([NetStage]): first *ask the server to repeat itself* — a
 * group request for the state the group is already in is answered by re-sending the authoritative
 * command to this session alone ("Client got lost, sending current state") — and only when that
 * second window expires too, act locally. The re-sent command is strictly better than the local
 * guess, because it carries the group's own `when` and position.
 *
 * ### Cancellation discipline
 * A net's job **owns its handle until its work is done**. A body that nulled its own handle the
 * moment it woke would make every later `cancel` a no-op against an orphaned job: a
 * `cancelPauseNet()` racing the body's main-thread hop could no longer stop it, and the local
 * fallback would then pause (or start!) a player the group had just moved. So the handle is
 * cleared only under an identity guard (`=== coroutineContext[Job]`, the same pattern the
 * scheduler uses), and a cancel always reaches the running body and takes effect at its next
 * suspension point. The residual window — a cancel landing while the main-thread block itself is
 * mid-flight — costs at most one action the very next state update corrects.
 */
internal class SyncPlayRecoveryNets(
    private val playerHandle: PlayerHandle,
    private val timeSync: SyncPlayTimeSync,
    private val mainDispatcher: CoroutineDispatcher,
    private val driver: Driver,
) {
    /**
     * What the nets read of the controller, and the few things they may ask of it.
     *
     * A narrow seam on purpose: the nets never touch the controller's session bookkeeping, only
     * its published state, its session scope, and the ordinary fire-and-forget group requests.
     */
    interface Driver {
        /** The controller's current state, exactly as the collectors see it. */
        fun state(): SyncPlayState

        /** Whether a player screen is attached — a detached member must never be *started*. */
        fun hasHost(): Boolean

        /** Launches on the session scope, so a stand-down cancels the net with the session. */
        fun launchNet(block: suspend CoroutineScope.() -> Unit): Job

        /** The elicit stage's ask — the controller's ordinary in-group-only request. */
        fun requestUnpause()

        /** The elicit stage's ask, pause direction. */
        fun requestPause()

        /** The local fallback started playback in lockstep with [anchor]; publish the phase. */
        fun onSelfSynced(anchor: SyncPlayAnchor)
    }

    /**
     * Where the *group* is on its own timeline, as of the last time it said it was playing.
     *
     * Not the same thing as the anchor in [SyncPlayPhase.Playing]: that one is established by an
     * applied unpause and is exact, this one is inferred from the group's state updates and is
     * only ever used when no unpause arrived at all ([selfSyncToGroup]). `null` whenever the
     * group is not known to be playing. Written by the controller (it owns the queue and the
     * parked-player readings the anchor is inferred from), consumed here.
     */
    var groupPlayingAnchor: SyncPlayAnchor? = null

    /** The play-direction safety net: fires when a completed handshake produced no command. */
    private var selfSyncJob: Job? = null

    /** The other half of it: fires when a paused group produced no pause command. */
    private var pauseNetJob: Job? = null

    /**
     * Starts the clock on "the group said go and nothing came".
     *
     * Armed after every `ready`, and by a `StateChanged(Playing)`; disarmed by the first command
     * applied. What it catches is the queue-advance wedge: the handshake completes, the
     * group's own state update says `Playing`, and the unpause that should have followed it
     * never arrives — leaving this member parked at 0:00 under the WAITING overlay while
     * everyone else watches. A group unpause cannot recover it either, because the group *is*
     * playing and the request no-ops.
     *
     * **Two windows, and the first one asks the server rather than guessing.** See [elicitRepeat]:
     * at [SELF_SYNC_TIMEOUT_MS] this sends a redundant `UnpauseGroupRequest`, which a group that
     * is already playing answers by re-sending its authoritative unpause to this session alone,
     * and re-arms itself once for [COMMAND_REPEAT_TIMEOUT_MS] ([NetStage.Fallback]). Only when
     * that window expires too does [selfSyncToGroup] act locally, off the inferred anchor. The
     * order matters because the inferred anchor is the weaker reading of the two: it comes from
     * the queue's `startPositionTicks`/`lastUpdate` and goes stale the moment the group pauses
     * and resumes without publishing a queue, which lands the self-sync seconds off and compounds
     * across cycles.
     *
     * Nothing here reports a `ready`: that would re-enter the readiness storm. A request is not
     * a report — the server answers it with a command, not with a wait.
     */
    fun armSelfSync(stage: NetStage = NetStage.Elicit) {
        selfSyncJob?.cancel()
        selfSyncJob =
            driver.launchNet {
                delay(if (stage == NetStage.Elicit) SELF_SYNC_TIMEOUT_MS else COMMAND_REPEAT_TIMEOUT_MS)
                // The handle deliberately still points at this job — see the class docs: a
                // disarm arriving during the work below must be able to cancel it.
                val asked = stage == NetStage.Elicit && elicitUnpauseRepeat()
                if (asked) {
                    if (selfSyncJob === coroutineContext[Job]) {
                        selfSyncJob = null
                        armSelfSync(NetStage.Fallback)
                    }
                } else {
                    selfSyncToGroup()
                    if (selfSyncJob === coroutineContext[Job]) selfSyncJob = null
                }
            }
    }

    fun cancelSelfSync() {
        selfSyncJob?.cancel()
        selfSyncJob = null
    }

    /**
     * Starts the clock on "the group said stop and nothing came" — the mirror of [armSelfSync].
     *
     * The failure it exists for is the pause direction, and it is the worse half: a `Pause` this
     * client never receives leaves the member playing on alone, while the phase quietly goes to
     * `Paused` and takes the drift monitor — which only runs in `Playing` — down with it. Nothing
     * then measures anything, and the member free-runs for the rest of the evening.
     *
     * Two things separate it from the play net. It is **not** gated on a host: pausing a
     * detached background player that the group has paused is right, where *starting* one would
     * be sound from nowhere. And it takes no other action — no seek, no report, no `play` — so
     * firing at a player that is already stopped costs nothing, which is what lets it be armed
     * from the group's state alone rather than from a proof that this member is out of step.
     * (The elicit stage below does want that proof, because a request is not free — see
     * [elicitPauseRepeat].)
     *
     * It has the same two windows as [armSelfSync]: a redundant `PauseGroupRequest` first, which
     * a group that is already paused answers with its authoritative pause — exact instant, exact
     * position — to this session alone, and only then the local pause. The re-sent command is
     * strictly better than the local one, because it parks this member *where the group is*
     * rather than merely stopping it where it happens to be.
     */
    fun armPauseNet(stage: NetStage = NetStage.Elicit) {
        pauseNetJob?.cancel()
        pauseNetJob =
            driver.launchNet {
                delay(if (stage == NetStage.Elicit) PAUSE_NET_TIMEOUT_MS else COMMAND_REPEAT_TIMEOUT_MS)
                // The handle deliberately still points at this job — see the class docs. The
                // elicit stage suspends on the player probe, and a `cancelPauseNet` arriving
                // during that hop must stop the ask and the fallback alike.
                val asked = stage == NetStage.Elicit && elicitPauseRepeat()
                if (asked) {
                    if (pauseNetJob === coroutineContext[Job]) {
                        pauseNetJob = null
                        armPauseNet(NetStage.Fallback)
                    }
                } else {
                    pauseToGroup()
                    if (pauseNetJob === coroutineContext[Job]) pauseNetJob = null
                }
            }
    }

    fun cancelPauseNet() {
        pauseNetJob?.cancel()
        pauseNetJob = null
    }

    /** Stands both nets down and forgets the anchor — the session is ending either way. */
    fun reset() {
        cancelSelfSync()
        cancelPauseNet()
        groupPlayingAnchor = null
    }

    /**
     * The [armSelfSync] half of [elicitRepeat]: a playing group this member is not keeping up
     * with.
     *
     * The phase check is the same one [selfSyncToGroup] opens with, so the ask goes out exactly
     * where the local fallback would have acted. A member already playing needs nothing repeated
     * — and a `StateChanged(Playing)` that merely trails its own applied command would otherwise
     * cost a request every single time the two arrive in that order.
     */
    private fun elicitUnpauseRepeat(): Boolean {
        val current = driver.state() as? SyncPlayState.InGroup ?: return false
        if (current.phase is SyncPlayPhase.Playing) return false
        return elicitRepeat(current, SyncPlayGroupState.Playing) { driver.requestUnpause() }
    }

    /**
     * The [armPauseNet] half of [elicitRepeat]: a paused group this member is still playing past.
     *
     * Gated on the player actually running, for the same reason [pauseToGroup] is: a member that
     * is already stopped is already where the group is, and has nothing to ask for.
     */
    private suspend fun elicitPauseRepeat(): Boolean {
        if (!isPlayerRunning()) return false
        // Read after the snapshot, not before: that hop to the main thread is long enough for
        // the group to have moved, and the state check is only worth anything when it is current.
        val current = driver.state() as? SyncPlayState.InGroup ?: return false
        return elicitRepeat(current, SyncPlayGroupState.Paused) { driver.requestPause() }
    }

    /**
     * Asks the server to say again what this member never heard — the first stage of both nets.
     *
     * The protocol has a recovery for exactly this. A group request
     * that asks for the state the group is *already* in is not a state change: the server reads
     * it as a member that has lost the thread and answers by re-sending the current command to
     * that one session, with the exact `When` and `PositionTicks` everyone else got —
     * `PausedGroupState.HandleRequest(PauseGroupRequest)` when `prevState == Paused`
     * (`PausedGroupState.cs`:88-93, "Client got lost, sending current state") and
     * `PlayingGroupState.HandleRequest(UnpauseGroupRequest)` when `prevState == Playing`
     * (`PlayingGroupState.cs`:80-86). The scheduler applies a re-sent command that never applied
     * locally (its "applied exactly once" guard remembers only what actually reached the player),
     * so the repeat lands and the ordinary command path — anchor, phase, drift monitor — does the
     * rest. That is the group's own timeline instead of this client's guess at it.
     *
     * **Only when the group is still where the net was armed for.** An `UnpauseGroupRequest`
     * sent to a group that has moved to WAITING is not a repeat, it is this member starting
     * everyone — so a group state that no longer matches falls straight through to the local
     * fallback, which is what the net did all along. The two callers add the other half of the
     * condition, "and this member really is out of step" ([elicitUnpauseRepeat],
     * [elicitPauseRepeat]), so the ask goes out exactly where the fallback would have acted.
     *
     * The ask itself goes through the controller's `request`: fire-and-forget, failure logged,
     * and a failed one simply leaves [NetStage.Fallback] to fire.
     *
     * @return whether the ask went out, which is the caller's cue to re-arm for a second window.
     */
    private fun elicitRepeat(
        current: SyncPlayState.InGroup,
        expected: SyncPlayGroupState,
        ask: () -> Unit,
    ): Boolean {
        if (current.groupState != expected) return false
        Timber.w(
            "SyncPlay group is %s and sent no command; asking the server to repeat itself",
            expected,
        )
        ask()
        return true
    }

    /**
     * Which of the two windows a safety net is in.
     *
     * The stage is carried rather than remembered, which is the whole of the loop guard: only an
     * [Elicit] net asks, and it can only ever re-arm as a [Fallback] one. A fresh episode — a
     * new `StateChanged`, a new `ready` — arms an [Elicit] net again and so gets one ask of its
     * own, but nothing inside an episode can produce a second.
     */
    internal enum class NetStage {
        /** The group said something and no command came: ask it to say so again. */
        Elicit,

        /** The repeat did not come either: act locally, off the inferred anchor. */
        Fallback,
    }

    @Suppress(
        // Guard chain over the group state a self-sync needs; each exit means the net has nothing to do.
        "ReturnCount",
    )
    private suspend fun selfSyncToGroup() {
        val current = driver.state() as? SyncPlayState.InGroup ?: return
        if (current.phase is SyncPlayPhase.Playing) return
        // Never on a detached player: a member with no screen open has told the group to stop
        // waiting on it, and starting the shared ExoPlayer behind nothing at
        // all would be sound from nowhere.
        if (!driver.hasHost()) return
        val anchor = groupPlayingAnchor ?: return
        val expected =
            (anchor.positionMs + Duration.between(anchor.at, timeSync.serverNow()).toMillis())
                .coerceAtLeast(0L)
        Timber.w("SyncPlay group is playing but sent no command; self-syncing to %d ms", expected)
        withContext(mainDispatcher) {
            playerHandle.seekTo(expected)
            playerHandle.play()
        }
        driver.onSelfSynced(anchor)
    }

    private suspend fun pauseToGroup() {
        if (driver.state() !is SyncPlayState.InGroup) return
        withContext(mainDispatcher) {
            // A player that is already stopped is already where the group is: no pause, and
            // nothing reported either way.
            if (!playerHandle.snapshot().isPlaying) return@withContext
            Timber.w("SyncPlay group is paused but sent no command; pausing this member")
            playerHandle.pause()
        }
    }

    /** Whether the player is actually running — the one reading no lost command can falsify. */
    private suspend fun isPlayerRunning(): Boolean =
        withContext(mainDispatcher) {
            playerHandle.snapshot().isPlaying
        }

    companion object {
        /**
         * How long a completed handshake waits for the group's command before syncing itself.
         *
         * Comfortably more than the delay the server builds into an unpause (twice the highest
         * member ping, floored at its 500 ms default), so the net only catches a command that is
         * not coming at all.
         */
        const val SELF_SYNC_TIMEOUT_MS = 3_000L

        /**
         * How long a paused group waits for its pause command before this member pauses itself.
         *
         * The same three seconds as [SELF_SYNC_TIMEOUT_MS], and for the same reason: it is
         * comfortably past the delay the server builds into a scheduled command, so only a
         * command that is not coming at all is caught. Deliberately not shorter — a member that
         * pauses ahead of the instant the group named is out of step just as surely.
         */
        const val PAUSE_NET_TIMEOUT_MS = 3_000L

        /**
         * The second window of both safety nets: how long the command elicited from the server
         * has to arrive before this member acts locally, in milliseconds.
         *
         * Shorter than the first windows because it measures something narrower. Those have to
         * outlast the delay the server *builds into* a scheduled command (twice the highest
         * member ping); this one covers one REST round trip and the application of a command
         * whose instant has, in the "client got lost" re-send, usually already passed — the
         * scheduler applies those immediately. Two seconds is generous for that on a tablet with
         * a busy main thread, and it is the whole of what the two-stage shape costs when the
         * server does not answer: the local fallback fires at five seconds rather than three.
         */
        const val COMMAND_REPEAT_TIMEOUT_MS = 2_000L
    }
}
