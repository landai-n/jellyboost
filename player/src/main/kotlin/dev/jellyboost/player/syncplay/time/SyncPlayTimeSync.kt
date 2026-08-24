package dev.jellyboost.player.syncplay.time

import dev.jellyboost.player.syncplay.model.TimeSyncSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncPlay schedules everything on the *server's* clock, so every conversion between the two clocks must go
 * through [toLocalTime] / [toServerTime] rather than assuming they agree.
 *
 * The estimator keeps the last [WINDOW_SIZE] samples, drops those whose round-trip exceeds
 * `max(1 s, 3 × median RTT)`, and means what survives. The threshold uses the **median**, not the mean: a
 * single huge RTT would drag a mean-based threshold up far enough to admit itself.
 */
@Singleton
internal class SyncPlayTimeSync
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        private val samples = ArrayDeque<TimeSyncSample>(WINDOW_SIZE)
        private val _offset = MutableStateFlow(Duration.ZERO)

        /** `serverClock − deviceClock`; [Duration.ZERO] until the first sample is recorded. */
        val offset: StateFlow<Duration> = _offset.asStateFlow()

        /** Includes samples the outlier filter rejects. */
        val sampleCount: Int
            @Synchronized get() = samples.size

        @Synchronized
        fun record(sample: TimeSyncSample): Duration {
            samples.addLast(sample)
            while (samples.size > WINDOW_SIZE) samples.removeFirst()
            return estimate().also { estimate ->
                _offset.value = estimate
                Timber.d(
                    "SyncPlay clock sample: offset %d ms rtt %d ms → estimate %d ms",
                    sample.offset.toMillis(),
                    sample.roundTrip.toMillis(),
                    estimate.toMillis(),
                )
            }
        }

        /** Must be called when the server changes: on sign-out, and on a group change. */
        @Synchronized
        fun reset() {
            samples.clear()
            _offset.value = Duration.ZERO
        }

        fun serverNow(): Instant = clock.instant().plus(offset.value)

        fun toLocalTime(serverTime: Instant): Instant = serverTime.minus(offset.value)

        fun toServerTime(localTime: Instant): Instant = localTime.plus(offset.value)

        private fun estimate(): Duration {
            if (samples.isEmpty()) return Duration.ZERO

            val threshold = outlierThreshold()
            val retained = samples.filter { it.roundTrip <= threshold }
            // Keep the window rather than report a zero offset known to be wrong.
            val used = retained.ifEmpty { samples }

            val totalNanos = used.fold(0L) { acc, sample -> acc + sample.offset.toNanos() }
            return Duration.ofNanos(totalNanos / used.size)
        }

        private fun outlierThreshold(): Duration {
            val sorted = samples.map { it.roundTrip }.sorted()
            val middle = sorted.size / 2
            val median =
                if (sorted.size % 2 == 1) {
                    sorted[middle]
                } else {
                    sorted[middle - 1].plus(sorted[middle]).dividedBy(2)
                }
            return maxOf(MIN_OUTLIER_THRESHOLD, median.multipliedBy(OUTLIER_MEDIAN_FACTOR))
        }

        companion object {
            /** ~40 s at the 5 s ping cadence: smooths a burst of jitter, still follows a clock being adjusted. */
            const val WINDOW_SIZE = 8

            /** Floor: on a LAN the median RTT is a few ms, and 3× that would reject ordinary samples. */
            val MIN_OUTLIER_THRESHOLD: Duration = Duration.ofSeconds(1)

            /** A round-trip this many times the median is treated as a stalled request. */
            const val OUTLIER_MEDIAN_FACTOR = 3L
        }
    }
