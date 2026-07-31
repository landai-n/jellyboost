package dev.jellyfinnative.player.syncplay.time

import dev.jellyfinnative.player.syncplay.api.SyncPlayApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
            var taken = 0
            while (currentCoroutineContext().isActive) {
                onOutcome(sampleOnce())
                taken++
                delay(if (taken < FAST_SAMPLES) FAST_INTERVAL_MS else STEADY_INTERVAL_MS)
            }
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
        private suspend fun sampleOnce(): Boolean =
            try {
                val sample = api.sampleServerTime()
                timeSync.record(sample)
                api.reportPing(sample.roundTrip.toMillis() / 2)
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.w(error, "A SyncPlay time sample failed; keeping the current offset")
                false
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
