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
 * Keeps the clock offset fresh and reports this client's latency; both come from the same exchange.
 * The server uses every member's latency to schedule commands, so a member that never pings degrades
 * the *group's* scheduling.
 *
 * Two cadences: [FAST_SAMPLES] fill the estimator's window so the first unpause after joining is
 * already accurate, then it settles to [STEADY_INTERVAL_MS] for the battery.
 */
@Singleton
internal class SyncPlayPinger
    @Inject
    constructor(
        private val api: SyncPlayApi,
        private val timeSync: SyncPlayTimeSync,
    ) {
        /** Wakes the cadence delay early; conflated because two pokes are worth one sample. */
        private val wake = Channel<Unit>(Channel.CONFLATED)

        /**
         * Takes the next sample now rather than after the current wait — for the app returning to the
         * foreground, where the connection may have been dead for minutes.
         *
         * Safe to call at any time: a poke with no loop running is kept, and [run] drops what it
         * finds so a stale poke cannot skew the next group's first cadence.
         */
        fun sampleNow() {
            wake.trySend(Unit)
        }

        /**
         * @param onOutcome `true` per completed exchange, `false` per failure. The only fixed-cadence
         *   server traffic there is, so it is the controller's only signal for a REST API that has
         *   stopped answering while the OS still reports the device online.
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
         * The half that needs no group, so the join flow can warm the clock *before* joining:
         * `GET /GetUtcTime` is a plain system call, where `POST /SyncPlay/Ping` answers `NotInGroup`.
         *
         * @return `null` when the exchange failed; a failure costs this sample and nothing else.
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
         * A failure must cost this sample and nothing else: ending the loop on one timed-out request
         * would leave every later command scheduled against a stale clock, silently.
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

            const val FAST_INTERVAL_MS = 1_000L

            const val STEADY_INTERVAL_MS = 5_000L
        }
    }
