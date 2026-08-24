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

internal enum class SyncPlayRejoinOutcome {
    Rejoined,
    Dissolved,
    Failed,
    Aborted,
}

/**
 * Runs on the confined single-threaded `@SyncPlayScope` (see `SyncPlayScopeModule`): the plain
 * `var`s below need no synchronisation because everything that touches them runs there.
 *
 * A membership given up deliberately is never rejoined — only a loss, or a removal that follows
 * connection trouble ([recentlyTroubled]).
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
     * Implemented by the controller; every call runs under its membership lock, so the loop here
     * can never race a join, a leave or a teardown.
     */
    interface SessionDriver {
        /** `true` only if there was a group to stand down. */
        suspend fun standDownForRejoin(target: SyncPlayGroupSummary): Boolean

        /** `true` only if the controller was idle. */
        suspend fun standUpFromIdle(target: SyncPlayGroupSummary): Boolean

        suspend fun attemptJoin(
            target: SyncPlayGroupSummary,
            attempt: Int,
        ): SyncPlayRejoinOutcome

        suspend fun tearDown(
            message: SyncPlayMessage?,
            pausePlayer: Boolean,
        )

        fun announce(message: SyncPlayMessage)
    }

    /** The group to take back if the server drops this session; cleared by every deliberate exit. */
    private var rejoinTarget: SyncPlayGroupSummary? = null

    private var rejoinJob: Job? = null

    /**
     * The one piece of session state that deliberately survives teardown, bounded by
     * [FOREGROUND_REJOIN_WINDOW_MS] and cleared by every deliberate exit.
     */
    private var lostMembership: LostMembership? = null

    /**
     * When the connection last misbehaved, on the server clock — what tells a removal caused by the
     * connection apart from one somebody decided on. Kept for [REJOIN_TROUBLE_WINDOW_MS], because
     * the removal is discovered by the *next* request rather than at the moment of the trouble.
     * Survives a stand-down; cleared by entering a group and by teardown.
     */
    private var troubledAt: Instant? = null

    fun markTrouble() {
        troubledAt = timeSync.serverNow()
    }

    private fun recentlyTroubled(): Boolean {
        val at = troubledAt ?: return false
        return Duration.between(at, timeSync.serverNow()).toMillis() <= REJOIN_TROUBLE_WINDOW_MS
    }

    fun onEnteredGroup(group: SyncPlayGroupSummary) {
        rejoinTarget = group
        troubledAt = null
        forgetLoss()
    }

    /**
     * Also the *usual* way membership is lost on the device: a three-second Wi-Fi drop outlasts the
     * reported-offline grace window once association, DHCP and the reachability probe are counted,
     * so nothing has the chance to discover a `NotInGroup`.
     */
    fun confirmLoss() {
        val target = rejoinTarget
        if (target == null) {
            // Singleton scope: teardown cancels the session scope, and a coroutine cannot be
            // relied on to finish the work that cancels it.
            scope.launch { driver.tearDown(SyncPlayMessage.ConnectionLost, pausePlayer = true) }
            return
        }
        if (rejoinJob?.isActive == true) return
        rejoinJob = scope.launch { rejoin(target) }
    }

    /**
     * A removal over a connection that has been well all along is the server or another client
     * saying so, and is obeyed; a removal after [recentlyTroubled] is the websocket having dropped
     * with nobody wanting any of it, and the membership is taken back.
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

    fun forgetDeliberately() {
        rejoinTarget = null
        forgetLoss()
    }

    fun forgetLoss() {
        lostMembership = null
    }

    /** [lostMembership] deliberately survives: it is the memory of the ending teardown performs. */
    fun onTeardown() {
        rejoinTarget = null
        rejoinJob?.cancel()
        rejoinJob = null
        troubledAt = null
    }

    /**
     * Order matters: cancel *and join* the rejoin before the caller sends `leaveGroup`. A join
     * still in flight can otherwise be processed after the leave, leaving a phantom participant
     * the group waits on while this app sits at Idle.
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
     * At most one attempt per foreground, deliberately: a failure leaves the memory alone so the
     * *next* foreground may try again inside the window.
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
     * The attempts are spaced because the first can be too early: the server removes a session from
     * its group only when the *old* websocket is reaped, which can land after this client has
     * already noticed and asked to come back.
     */
    private suspend fun rejoin(target: SyncPlayGroupSummary) {
        if (!driver.standDownForRejoin(target)) return
        runRejoinAttempts(target, quiet = false)
    }

    private suspend fun rejoinFromIdle(target: SyncPlayGroupSummary) {
        if (!driver.standUpFromIdle(target)) return
        runRejoinAttempts(target, quiet = true)
    }

    /**
     * @param quiet suppresses the two *endings* — "connection lost" and "the group has ended" — so
     *   the foreground re-check does not announce a dead group every time the app is opened.
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
        rememberLoss(target)
        endRejoin(SyncPlayMessage.ConnectionLost.takeUnless { quiet })
    }

    /**
     * The radio comes back several seconds after it went (association, DHCP and the reachability
     * probe all follow), so an attempt fired the instant a loss is confirmed is thrown away.
     */
    private suspend fun awaitOnline(): Boolean {
        if (connectionState.state.value.isOnline) return true
        return withTimeoutOrNull(REJOIN_RETRY_DELAY_MS) {
            connectionState.state.first { it.isOnline }
        } != null
    }

    /** [rejoinJob] is nulled *before* the teardown so [onTeardown] finds nothing left to cancel. */
    private suspend fun endRejoin(message: SyncPlayMessage?) {
        rejoinJob = null
        driver.tearDown(message, pausePlayer = false)
    }

    /**
     * The instant is **not** refreshed for a group already remembered: the window counts from the
     * loss, not from the last failed foreground re-check.
     */
    private fun rememberLoss(group: SyncPlayGroupSummary) {
        if (lostMembership?.group?.id == group.id) return
        lostMembership = LostMembership(group, clock.instant())
        Timber.i("Remembering the lost SyncPlay group %s for %d ms", group.id, FOREGROUND_REJOIN_WINDOW_MS)
    }

    private data class LostMembership(
        val group: SyncPlayGroupSummary,
        val at: Instant,
    )

    companion object {
        /**
         * Three rather than one because the server can still be reaping the old session when the
         * first attempt lands; bounded so the user can get out of a group they have left.
         */
        const val REJOIN_MAX_ATTEMPTS = 3

        /** Spacing between rejoin attempts, in milliseconds. */
        const val REJOIN_RETRY_DELAY_MS = 2_000L

        /**
         * How long after connection trouble a removal is still blamed on it, in milliseconds.
         * Has to outlast a couple of ping-loop cycles (five seconds each), since the removal is
         * discovered by the next request rather than at the moment of the trouble.
         */
        const val REJOIN_TROUBLE_WINDOW_MS = 30_000L

        /**
         * How long an involuntarily lost membership is still worth taking back, in milliseconds.
         * Ten minutes is a judgement about what the user meant, not a protocol value.
         */
        const val FOREGROUND_REJOIN_WINDOW_MS = 600_000L
    }
}
