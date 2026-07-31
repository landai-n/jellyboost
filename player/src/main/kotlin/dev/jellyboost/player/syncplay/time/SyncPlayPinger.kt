package dev.jellyboost.player.syncplay.time

import dev.jellyboost.player.syncplay.api.SyncPlayApi
import dev.jellyboost.player.syncplay.model.TimeSyncSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the clock offset fresh, and tells the server what this client's latency is.
 *
 * Two jobs in one loop because they come from the same exchange. `GET /GetUtcTime` gives the four
 * timestamps [SyncPlayTimeSync] needs, and half its round-trip is what `POST /SyncPlay/Ping`
 * reports — the server uses every member's latency to choose how far in the future to schedule a
 * command, so a member that never pings makes the *group's* scheduling worse, not just its own.
 *
 * The cadence has two speeds on purpose: [FAST_SAMPLES] a second apart fills the estimator's window
 * quickly enough that the first unpause after joining is already accurate, then it settles to
 * [STEADY_INTERVAL_MS] because clock drift is a matter of milliseconds per minute and there is a
 * battery to think about.
 *
 * Runs for exactly as long as the group does.
 */
@Singleton
class SyncPlayPinger
    @Inject
    constructor(
        private val api: SyncPlayApi,
        private val timeSync: SyncPlayTimeSync,
    ) {
        /** Wakes the cadence delay early; conflated because two pokes are worth one sample. */
        private val wake = Channel<Unit>(Channel.CONFLATED)

        /**
         * Asks the loop to take its next sample now instead of at the end of the current wait.
         *
         * What it is for: the app coming back to the foreground after the platform quietly cut its
         * network (DECISIONS.md 2026-07-31). The connection may have been dead for minutes and
         * nothing here knows it, so the useful thing is to find out at once rather than up to
         * [STEADY_INTERVAL_MS] later — the three-failure streak that confirms a loss then starts
         * immediately.
         *
         * Conflated and safe to call at any time: a poke with no loop running is kept for the next
         * one, and [run] drops whatever it finds so a stale poke from a previous group cannot make
         * the new one's first cadence wrong.
         */
        fun sampleNow() {
            wake.trySend(Unit)
        }

        /**
         * Samples the server clock until cancelled.
         *
         * @param onOutcome called with `true` for every completed exchange and `false` for every
         *   failed one. This loop is the only thing that talks to the server on a fixed cadence, so
         *   it is also the only honest signal for "the REST API has stopped answering" — which is
         *   how the controller notices a connection that is gone while the OS still says the device
         *   is online (STATUS.md, DoD session #1, B8).
         */
        suspend fun run(onOutcome: (Boolean) -> Unit = {}) {
            while (wake.tryReceive().isSuccess) Unit
            var taken = 0
            while (currentCoroutineContext().isActive) {
                onOutcome(sampleOnce())
                taken++
                val wait = if (taken < FAST_SAMPLES) FAST_INTERVAL_MS else STEADY_INTERVAL_MS
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }

        /**
         * One clock exchange, recorded — and nothing else.
         *
         * Split out of [sampleOnce] because it is the half that needs no group: `GET /GetUtcTime` is
         * a plain system call, where `POST /SyncPlay/Ping` is a group request the server answers with
         * `NotInGroup` when the session is not in one yet. That is what lets the join flow warm the
         * clock *before* it joins ([SyncPlayTimeSync.offset] is `ZERO` until something measures it,
         * and the first command can arrive the moment the join returns).
         *
         * @return the sample, or `null` when the exchange failed — a failure costs this sample and
         *   nothing else, and never blocks the caller.
         */
        suspend fun sampleClock(): TimeSyncSample? =
            try {
                api.sampleServerTime().also(timeSync::record)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.w(error, "A SyncPlay time sample failed; keeping the current offset")
                null
            }

        /**
         * One exchange: measure, feed the estimator, report the latency.
         *
         * A failure costs this sample and nothing else. The loop is the only thing keeping the
         * offset current, and letting one timed-out request end it would leave the client scheduling
         * every later command against a stale clock — silently, and for the rest of the group
         * session.
         *
         * @return `true` when the exchange completed.
         */
        private suspend fun sampleOnce(): Boolean {
            val sample = sampleClock() ?: return false
            return try {
                api.reportPing(sample.roundTrip.toMillis() / 2)
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.w(error, "A SyncPlay ping report failed; this cycle counts as a failure")
                false
            }
        }

        companion object {
            /** Samples taken at [FAST_INTERVAL_MS] right after joining, before settling down. */
            const val FAST_SAMPLES = 3

            /** Spacing of the first [FAST_SAMPLES] samples, in milliseconds. */
            const val FAST_INTERVAL_MS = 1_000L

            /** Spacing of every sample after that, in milliseconds. */
            const val STEADY_INTERVAL_MS = 5_000L
        }
    }
