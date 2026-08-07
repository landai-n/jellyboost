package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyboost.player.syncplay.time.SyncPlayTimeSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What one rejoin attempt came to — answered by [SyncPlayRejoinPolicy.SessionDriver.attemptJoin]. */
internal enum class SyncPlayRejoinOutcome {
    Rejoined,
    Dissolved,
    Failed,
    Aborted,
}

/**
 * Decides when a lost membership is taken back, and runs the attempts that take it.
 *
 * Extracted from [SyncPlayController] (audit CPX-1/ARCH-5) because the rejoin machinery owns a
 * disjoint slice of state — the remembered [rejoinTarget], the attempt loop's [rejoinJob], the
 * [lostMembership] memory that survives teardown, and the [troubledAt] reading that tells a
 * removal-by-connection apart from a removal-by-decision. The membership transitions themselves
 * (stand down, attempt a join, tear down) stay the controller's, reached through [SessionDriver]
 * under the controller's own membership lock — the loop drives the same `performJoin`/`standDown`
 * entry points every other path uses.
 *
 * Runs on the confined single-threaded `@SyncPlayScope` (see `SyncPlayScopeModule`), like the
 * controller and the scheduler: the plain `var`s below are safe because everything that touches
 * them runs there.
 *
 * ### The rules, in one place
 * - the group is remembered in [rejoinTarget] for as long as membership is *not* given up
 *   deliberately — leaving, sign-out, `LibraryAccessDenied` and `GroupGone` all forget it, and so
 *   does a removal that arrives over a connection which was never in trouble ([recentlyTroubled]);
 * - a loss or a removal-after-trouble stands the session down and runs up to [REJOIN_MAX_ATTEMPTS]
 *   attempts, [REJOIN_RETRY_DELAY_MS] apart, of "list the groups, and if ours is still there, join
 *   it" — the ordinary join flow, handshake and all;
 * - exhausted attempts are remembered in [lostMembership] so the next foreground can ask once
 *   more, inside [FOREGROUND_REJOIN_WINDOW_MS]; a group no longer listed has dissolved and is
 *   forgotten on the spot.
 */
internal class SyncPlayRejoinPolicy(
    private val connectionState: ConnectionStateProvider,
    private val timeSync: SyncPlayTimeSync,
    // The *device* clock, deliberately, and only for [lostMembership]: `timeSync.serverNow()` is
    // reset by the very teardown that memory has to outlive.
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val driver: SessionDriver,
) {
    /**
     * The membership operations the loop drives — implemented by the controller, each one under
     * its own membership lock so the loop can never race a join, a leave or a teardown.
     */
    interface SessionDriver {
        /** If in a group: stand the session down into `Rejoining` and answer `true`. */
        suspend fun standDownForRejoin(target: SyncPlayGroupSummary): Boolean

        /** If idle (the foreground re-check): enter `Rejoining` and answer `true`. */
        suspend fun standUpFromIdle(target: SyncPlayGroupSummary): Boolean

        /** One attempt: is the group still listed, and did the ordinary join flow enter it? */
        suspend fun attemptJoin(
            target: SyncPlayGroupSummary,
            attempt: Int,
        ): SyncPlayRejoinOutcome

        /** The ordinary teardown — the rejoin's endings hand over to it. */
        suspend fun tearDown(
            message: SyncPlayMessage?,
            pausePlayer: Boolean,
        )

        /** Puts a message on screen without ending anything ("Rejoined the group"). */
        fun announce(message: SyncPlayMessage)
    }

    /**
     * The group to take back if the server drops this session, or `null` if leaving would be
     * nobody's mistake.
     *
     * Set on entering a group and cleared by every *deliberate* exit, which is the whole of the
     * "never auto-rejoin something the user or the server meant to end" rule.
     */
    private var rejoinTarget: SyncPlayGroupSummary? = null

    /** The rejoin attempt loop, so leaving or signing out can abort it. */
    private var rejoinJob: Job? = null

    /**
     * The group this client was thrown out of against its will, and when — the one thing that
     * survives the controller's teardown.
     *
     * Everything else about a group session is forgotten when the session ends, deliberately.
     * This is not, because the ending it records is the one nobody chose: the platform cut the
     * app's network while it was backgrounded, the rejoin attempts all failed for the same
     * reason, and the group is very probably still there with the user watching it somewhere
     * else. Without a memory the controller sits at [SyncPlayState.Idle] for ever knowing
     * nothing; with one, the foreground re-check can ask for the group back at the one moment
     * the ask can succeed.
     *
     * Bounded by [FOREGROUND_REJOIN_WINDOW_MS] and cleared by every deliberate exit — see
     * [rememberLoss] and [forgetLoss].
     */
    private var lostMembership: LostMembership? = null

    /**
     * When the connection last misbehaved, on the server clock.
     *
     * What tells "the server dropped us because the connection went" apart from "the server
     * dropped us on purpose": only the first is worth rejoining, and only the first is preceded
     * by connectivity going away, a ping failing, or the socket leaving `Connected`. Kept for
     * [REJOIN_TROUBLE_WINDOW_MS], because the removal is discovered by the *next* request rather
     * than at the moment of the trouble. Survives a stand-down (the trouble that caused it is
     * the same episode); cleared by entering a group and by teardown ([onTeardown]).
     */
    private var troubledAt: Instant? = null

    /** Records that the connection misbehaved just now; see [troubledAt]. */
    fun markTrouble() {
        troubledAt = timeSync.serverNow()
    }

    /** Whether the connection misbehaved recently enough to explain a removal. */
    private fun recentlyTroubled(): Boolean {
        val at = troubledAt ?: return false
        return Duration.between(at, timeSync.serverNow()).toMillis() <= REJOIN_TROUBLE_WINDOW_MS
    }

    /** A group was entered (first join or rejoin): it is the new target, and nothing is lost. */
    fun onEnteredGroup(group: SyncPlayGroupSummary) {
        rejoinTarget = group
        troubledAt = null
        // Whatever was lost has been recovered, or replaced by a group the user chose.
        forgetLoss()
    }

    /**
     * The connection is gone as far as this client can tell — ask for the group back first.
     *
     * It used to be the end of the group outright. On the device it is also the *usual* way the
     * membership is lost: a three-second Wi-Fi drop costs more than the grace window of
     * reported-offline once association, DHCP and the reachability probe are counted, so the
     * grace expires before anything has had the chance to discover a `NotInGroup`. Handing it to
     * the rejoin loop changes nothing when the connection really has gone — every attempt is
     * gated on being online, and the ending is the same [SyncPlayMessage.ConnectionLost] a few
     * seconds later, with the player paused from this moment either way.
     */
    fun confirmLoss() {
        val target = rejoinTarget
        if (target == null) {
            // Always on the singleton scope: teardown cancels the session scope, and a coroutine
            // cannot be relied on to finish the work that cancels it.
            scope.launch { driver.tearDown(SyncPlayMessage.ConnectionLost, pausePlayer = true) }
            return
        }
        if (rejoinJob?.isActive == true) return
        rejoinJob = scope.launch { rejoin(target) }
    }

    /**
     * The server no longer has this session in the group — decide whether that was meant.
     *
     * A removal over a connection that has been well all along is the server or another client
     * saying so, and is obeyed exactly as it always was. A removal after [recentlyTroubled] is
     * the websocket having dropped, `OnSessionEnded` having called `LeaveGroup` on our behalf,
     * and nobody having wanted any of it — so the membership is taken back.
     */
    fun onMembershipGone() {
        val target = rejoinTarget
        if (target == null || !recentlyTroubled()) {
            Timber.i("Removed from the SyncPlay group with the connection healthy; not rejoining")
            forgetDeliberately()
            scope.launch { driver.tearDown(SyncPlayMessage.RemovedFromGroup, pausePlayer = false) }
            return
        }
        if (rejoinJob?.isActive == true) return
        // The singleton scope, always: the first thing a rejoin does is cancel the session scope.
        rejoinJob = scope.launch { rejoin(target) }
    }

    /** Gives up the group deliberately: no rejoin will follow, and none on the next foreground. */
    fun forgetDeliberately() {
        rejoinTarget = null
        forgetLoss()
    }

    /** Drops the lost-membership memory alone: this exit was chosen, or the group recovered. */
    fun forgetLoss() {
        lostMembership = null
    }

    /**
     * Teardown's half: forgets the group and stops any attempt to take it back.
     *
     * [lostMembership] deliberately survives — it is the memory of the ending teardown performs,
     * written by [rememberLoss] just before the exhausted loop hands over to it.
     */
    fun onTeardown() {
        rejoinTarget = null
        rejoinJob?.cancel()
        rejoinJob = null
        troubledAt = null
    }

    /**
     * [onTeardown]'s abort, but waited out — for the deliberate exits that go on to tell the
     * server.
     *
     * The order is the point (audit SP-10): a rejoin can be suspended inside `api.joinGroup`,
     * and a leave sent while that join is still in flight can be answered *before* it — the app
     * ends at Idle while the server keeps the session in the group, a phantom participant the
     * others wait on. Cancelling first and joining the job means any join the server already
     * processed is followed by the `leaveGroup` the caller sends next, never the other way
     * round.
     */
    suspend fun abandonRejoinAndAwait() {
        rejoinTarget = null
        rejoinJob?.let { job ->
            job.cancel()
            job.join()
        }
        rejoinJob = null
    }

    /**
     * Asks for a recently, involuntarily lost group back — the app-foregrounded half.
     *
     * Deliberately not a loop and not a retry schedule: it runs at most once per foreground, and
     * a failure leaves the memory alone so the *next* foreground may try again inside the
     * window. A memory that has expired is dropped here rather than left to rot, which is what
     * makes "the user came back much later, to something else entirely" cost nothing.
     */
    fun resumeLostMembership() {
        val lost = lostMembership ?: return
        val age = Duration.between(lost.at, clock.instant()).toMillis()
        if (age !in 0..FOREGROUND_REJOIN_WINDOW_MS) {
            Timber.i("The lost SyncPlay group %s is too old to take back; forgetting it", lost.group.id)
            lostMembership = null
            return
        }
        if (rejoinJob?.isActive == true) return
        rejoinJob = scope.launch { rejoinFromIdle(lost.group) }
    }

    /**
     * Stands the lost session down and tries [REJOIN_MAX_ATTEMPTS] times to get [target] back.
     *
     * The attempts are spaced because the first one can legitimately be too early: the server
     * removes a session from its group when the *old* websocket is finally reaped, which can
     * land after this client has already noticed and asked to come back.
     */
    private suspend fun rejoin(target: SyncPlayGroupSummary) {
        if (!driver.standDownForRejoin(target)) return
        runRejoinAttempts(target, quiet = false)
    }

    /** A rejoin that starts from idle rather than from a session being stood down. */
    private suspend fun rejoinFromIdle(target: SyncPlayGroupSummary) {
        if (!driver.standUpFromIdle(target)) return
        runRejoinAttempts(target, quiet = true)
    }

    /**
     * The attempt loop itself, shared by the loss path and by the foreground re-check.
     *
     * @param quiet suppresses the two *endings* — "connection lost" and "the group has ended" —
     *   without changing anything the protocol does. The re-check runs on every foreground, and
     *   a group that is genuinely gone would otherwise announce itself every time the user
     *   opened the app. A success is still announced: that one is news.
     */
    private suspend fun runRejoinAttempts(
        target: SyncPlayGroupSummary,
        quiet: Boolean,
    ) {
        for (attempt in 1..REJOIN_MAX_ATTEMPTS) {
            if (attempt > 1) delay(REJOIN_RETRY_DELAY_MS)
            if (!awaitOnline()) {
                Timber.w("Still offline; SyncPlay rejoin attempt %d spent waiting", attempt)
                continue
            }
            when (driver.attemptJoin(target, attempt)) {
                SyncPlayRejoinOutcome.Rejoined -> {
                    rejoinJob = null
                    Timber.i("Rejoined the SyncPlay group %s on attempt %d", target.id, attempt)
                    driver.announce(SyncPlayMessage.Rejoined)
                    return
                }

                // Listed groups came back without ours: we were its last member and it is gone.
                SyncPlayRejoinOutcome.Dissolved -> {
                    Timber.w("The SyncPlay group %s no longer exists; staying out", target.id)
                    forgetLoss()
                    endRejoin(SyncPlayMessage.GroupEnded.takeUnless { quiet })
                    return
                }

                // Left or signed out from under us; whoever did it owns the teardown.
                SyncPlayRejoinOutcome.Aborted -> return

                SyncPlayRejoinOutcome.Failed -> Unit
            }
        }
        Timber.w("Could not rejoin the SyncPlay group after %d attempts", REJOIN_MAX_ATTEMPTS)
        // Remembered on the way out, so returning to the app can ask once more. A re-check that
        // fails keeps the memory it already had, original instant and all.
        rememberLoss(target)
        endRejoin(SyncPlayMessage.ConnectionLost.takeUnless { quiet })
    }

    /**
     * Waits, briefly, for a network worth spending an attempt on.
     *
     * The radio comes back several seconds after it went — Wi-Fi association, DHCP and the
     * reachability probe all happen after `svc wifi enable` returns — so an attempt fired the
     * instant the loss is confirmed is an attempt thrown away. Bounded by the same
     * [REJOIN_RETRY_DELAY_MS] as the spacing, so a connection that is genuinely gone still ends
     * at [SyncPlayState.Idle] within a handful of seconds.
     *
     * @return `false` when the wait ran out and the device is still offline.
     */
    private suspend fun awaitOnline(): Boolean {
        if (connectionState.state.value.isOnline) return true
        return withTimeoutOrNull(REJOIN_RETRY_DELAY_MS) {
            connectionState.state.first { it.isOnline }
        } != null
    }

    /**
     * The rejoin gave up; hand over to the ordinary teardown without cancelling this coroutine.
     *
     * [rejoinJob] is nulled *before* the teardown so its [onTeardown] abort finds nothing to
     * cancel — this coroutine is the loop, and it is already ending.
     *
     * @param message `null` for the foreground re-check, which ends silently — see
     *   [runRejoinAttempts].
     */
    private suspend fun endRejoin(message: SyncPlayMessage?) {
        rejoinJob = null
        driver.tearDown(message, pausePlayer = false)
    }

    /**
     * Records that [group] was lost without anyone choosing it.
     *
     * The instant is **not** refreshed for a group already remembered: the window is counted
     * from the loss, and a failed re-check on every foreground would otherwise walk the deadline
     * forward for as long as the user kept opening the app.
     */
    private fun rememberLoss(group: SyncPlayGroupSummary) {
        if (lostMembership?.group?.id == group.id) return
        lostMembership = LostMembership(group, clock.instant())
        Timber.i("Remembering the lost SyncPlay group %s for %d ms", group.id, FOREGROUND_REJOIN_WINDOW_MS)
    }

    /** A group that was lost involuntarily, and the device instant it was lost at. */
    private data class LostMembership(
        val group: SyncPlayGroupSummary,
        val at: Instant,
    )

    companion object {
        /**
         * How many times a lost membership is asked for back before the group is given up on.
         *
         * Three rather than one because the server can still be reaping the old session when the
         * first attempt lands, and bounded rather than open-ended because a client that retries
         * for ever is a client the user cannot get out of a group they are no longer in.
         */
        const val REJOIN_MAX_ATTEMPTS = 3

        /** Spacing between rejoin attempts, in milliseconds. */
        const val REJOIN_RETRY_DELAY_MS = 2_000L

        /**
         * How long after connection trouble a removal is still blamed on it, in milliseconds.
         *
         * The removal is discovered by the *next* request rather than at the moment of the
         * trouble — the ping loop's five-second cadence is the usual finder — so the window has
         * to outlast a couple of those. Outside it, a removal is somebody's decision and is
         * obeyed.
         */
        const val REJOIN_TROUBLE_WINDOW_MS = 30_000L

        /**
         * How long an involuntarily lost membership is still worth taking back, in milliseconds.
         *
         * Ten minutes is a judgement about what the user meant, not about the protocol: the case
         * this exists for is the app being backgrounded while the same tablet drives the group
         * from jellyfin-web, where coming back within a few minutes means "I never left". Much
         * longer and the app starts re-joining groups the user has genuinely finished with;
         * much shorter and an evening's browsing in another app costs the group anyway.
         */
        const val FOREGROUND_REJOIN_WINDOW_MS = 600_000L
    }
}
