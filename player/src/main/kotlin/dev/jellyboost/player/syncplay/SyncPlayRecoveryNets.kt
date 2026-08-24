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
 * Runs on the confined single-threaded `@SyncPlayScope` (see `SyncPlayScopeModule`): the plain
 * `var`s below need no synchronisation because everything that touches them runs there.
 *
 * A net's job **owns its handle until its work is done**: the handle is cleared only under an
 * identity guard (`=== coroutineContext[Job]`), so a `cancel` always reaches the running body
 * rather than no-opping against an orphaned job that would then pause — or start! — a player the
 * group has just moved.
 */
internal class SyncPlayRecoveryNets(
    private val playerHandle: PlayerHandle,
    private val timeSync: SyncPlayTimeSync,
    private val mainDispatcher: CoroutineDispatcher,
    private val driver: Driver,
) {
    interface Driver {
        fun state(): SyncPlayState

        /** Whether a player screen is attached — a detached member must never be *started*. */
        fun hasHost(): Boolean

        /** Launches on the session scope, so a stand-down cancels the net with the session. */
        fun launchNet(block: suspend CoroutineScope.() -> Unit): Job

        fun requestUnpause()

        fun requestPause()

        fun onSelfSynced(anchor: SyncPlayAnchor)
    }

    /**
     * Where the *group* is on its own timeline. Inferred from its state updates rather than
     * established by an applied unpause, so it is the weaker reading and is used only when no
     * command arrived at all ([selfSyncToGroup]). `null` when the group is not known to be playing.
     * Written by the controller, consumed here.
     */
    var groupPlayingAnchor: SyncPlayAnchor? = null

    private var selfSyncJob: Job? = null

    private var pauseNetJob: Job? = null

    /**
     * Armed after every `ready` and by a `StateChanged(Playing)`; disarmed by the first command
     * applied. The first window asks the server rather than guessing ([elicitRepeat]), because the
     * inferred anchor the local fallback works from goes stale as soon as the group pauses and
     * resumes without publishing a queue.
     *
     * Nothing here reports a `ready`: that would re-enter the readiness storm. A request is not a
     * report — the server answers it with a command, not with a wait.
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
     * The mirror of [armSelfSync], with two differences. It is **not** gated on a host: pausing a
     * detached background player that the group has paused is right, where *starting* one would be
     * sound from nowhere. And it takes no other action — no seek, no report, no `play` — so it can
     * be armed from the group's state alone rather than from proof this member is out of step.
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

    fun reset() {
        cancelSelfSync()
        cancelPauseNet()
        groupPlayingAnchor = null
    }

    /**
     * The phase check is the same one [selfSyncToGroup] opens with: a `StateChanged(Playing)` that
     * merely trails its own applied command must not cost a request every time.
     */
    private fun elicitUnpauseRepeat(): Boolean {
        val current = driver.state() as? SyncPlayState.InGroup ?: return false
        if (current.phase is SyncPlayPhase.Playing) return false
        return elicitRepeat(current, SyncPlayGroupState.Playing) { driver.requestUnpause() }
    }

    /** Gated on the player running, like [pauseToGroup]: a stopped member is already where the group is. */
    private suspend fun elicitPauseRepeat(): Boolean {
        if (!isPlayerRunning()) return false
        // Read after the snapshot, not before: that hop to the main thread is long enough for
        // the group to have moved, and the state check is only worth anything when it is current.
        val current = driver.state() as? SyncPlayState.InGroup ?: return false
        return elicitRepeat(current, SyncPlayGroupState.Paused) { driver.requestPause() }
    }

    /**
     * A group request for the state the group is *already* in is not a state change: the server
     * reads it as a member that lost the thread and re-sends the current command — same `When`,
     * same `PositionTicks` as everyone else got — to that one session ("Client got lost, sending
     * current state"). Only ever sent while the group is still in the state the net was armed for:
     * an `UnpauseGroupRequest` to a group that has moved to WAITING would start everyone.
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
     * The stage is carried rather than remembered, which is the whole of the loop guard: only an
     * [Elicit] net asks, and it can only re-arm as a [Fallback] one.
     */
    internal enum class NetStage {
        Elicit,

        Fallback,
    }

    @Suppress(
        "ReturnCount",
    )
    private suspend fun selfSyncToGroup() {
        val current = driver.state() as? SyncPlayState.InGroup ?: return
        if (current.phase is SyncPlayPhase.Playing) return
        // Never on a detached player: a member with no screen open has told the group to stop
        // waiting on it, and starting the shared ExoPlayer would be sound from nowhere.
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
            if (!playerHandle.snapshot().isPlaying) return@withContext
            Timber.w("SyncPlay group is paused but sent no command; pausing this member")
            playerHandle.pause()
        }
    }

    private suspend fun isPlayerRunning(): Boolean =
        withContext(mainDispatcher) {
            playerHandle.snapshot().isPlaying
        }

    companion object {
        /**
         * Comfortably more than the delay the server builds into an unpause (twice the highest
         * member ping, floored at its 500 ms default), so only a command that is not coming at all
         * is caught.
         */
        const val SELF_SYNC_TIMEOUT_MS = 3_000L

        /**
         * The same three seconds as [SELF_SYNC_TIMEOUT_MS] and for the same reason. Deliberately
         * not shorter — a member that pauses ahead of the instant the group named is out of step
         * just as surely.
         */
        const val PAUSE_NET_TIMEOUT_MS = 3_000L

        /**
         * The second window of both nets, in milliseconds: shorter than the first ones because it
         * covers one REST round trip and a command whose instant has usually already passed, not
         * the delay the server builds into a scheduled command.
         */
        const val COMMAND_REPEAT_TIMEOUT_MS = 2_000L
    }
}
