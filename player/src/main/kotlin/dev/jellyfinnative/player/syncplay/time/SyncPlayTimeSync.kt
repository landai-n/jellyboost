package dev.jellyfinnative.player.syncplay.time

import dev.jellyfinnative.player.syncplay.model.TimeSyncSample
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
 * How far this device's clock is from the server's, estimated NTP-style from `GET /GetUtcTime`
 * exchanges.
 *
 * SyncPlay schedules everything on the *server's* clock: a `SendCommand` says "unpause at
 * 20:41:03.250" and every member is expected to act at that instant. A device clock that is two
 * seconds fast plays two seconds early, which is exactly the kind of drift the group cannot see and
 * the user immediately can. So the offset is measured rather than assumed, and every conversion
 * between server time and device time goes through [toLocalTime] / [toServerTime].
 *
 * The estimator is deliberately boring:
 *
 * - keep the last [WINDOW_SIZE] samples, oldest evicted;
 * - drop samples whose round-trip exceeds `max(1 s, 3 × median RTT)` — one stalled request on a
 *   congested network otherwise poisons the estimate for the next 8 samples;
 * - the estimate is the mean offset of what survives.
 *
 * The median (not the mean) sets the outlier threshold precisely because a single huge RTT would
 * drag a mean-based threshold up far enough to admit itself. With a single sample nothing can be
 * an outlier, so the first exchange takes effect immediately — a group joined on a bad connection
 * still starts in sync rather than starting at zero offset.
 *
 * Pure Kotlin and injected with [Clock], so all of it is unit-testable with a fixed clock; the
 * class is `@Singleton` because the offset belongs to the connection, not to a screen.
 */
@Singleton
class SyncPlayTimeSync
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        private val samples = ArrayDeque<TimeSyncSample>(WINDOW_SIZE)
        private val _offset = MutableStateFlow(Duration.ZERO)

        /**
         * Current estimate of `serverClock − deviceClock`.
         *
         * [Duration.ZERO] until the first sample is recorded, which is the honest default: with no
         * measurement the best guess is that the two clocks agree.
         */
        val offset: StateFlow<Duration> = _offset.asStateFlow()

        /** Number of samples currently in the window, including ones the outlier filter rejects. */
        val sampleCount: Int
            @Synchronized get() = samples.size

        /** Adds [sample] to the rolling window and returns the re-computed [offset]. */
        @Synchronized
        fun record(sample: TimeSyncSample): Duration {
            samples.addLast(sample)
            while (samples.size > WINDOW_SIZE) samples.removeFirst()
            return estimate().also { estimate ->
                _offset.value = estimate
                // Every desync argument starts with "whose clock was wrong": one line per sample is
                // what makes the estimate, its inputs, and an outlier being rejected all readable
                // from a device log alone.
                Timber.d(
                    "SyncPlay clock sample: offset %d ms rtt %d ms → estimate %d ms",
                    sample.offset.toMillis(),
                    sample.roundTrip.toMillis(),
                    estimate.toMillis(),
                )
            }
        }

        /** Forgets every sample — on sign-out, or when the group (and so the server) changes. */
        @Synchronized
        fun reset() {
            samples.clear()
            _offset.value = Duration.ZERO
        }

        /** Now, on the server's clock. */
        fun serverNow(): Instant = clock.instant().plus(offset.value)

        /** The device-clock instant matching a server-clock one — how a `SendCommand.when` is scheduled. */
        fun toLocalTime(serverTime: Instant): Instant = serverTime.minus(offset.value)

        /** The server-clock instant matching a device-clock one — how buffering/ready are stamped. */
        fun toServerTime(localTime: Instant): Instant = localTime.plus(offset.value)

        private fun estimate(): Duration {
            if (samples.isEmpty()) return Duration.ZERO

            val threshold = outlierThreshold()
            val retained = samples.filter { it.roundTrip <= threshold }
            // Can only happen if every retained duration is negative-median nonsense; keep the
            // window rather than reporting a zero offset we know to be wrong.
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
            /** Samples kept. Eight covers ~40 s at the plan's 5 s ping cadence — long enough to smooth
             * a burst of jitter, short enough to follow a clock that is genuinely being adjusted. */
            const val WINDOW_SIZE = 8

            /** Floor on the outlier threshold: on a fast LAN the median RTT is a few ms, and 3× that
             * would reject perfectly ordinary samples. */
            val MIN_OUTLIER_THRESHOLD: Duration = Duration.ofSeconds(1)

            /** A round-trip more than this many times the median is treated as a stalled request. */
            const val OUTLIER_MEDIAN_FACTOR = 3L
        }
    }
