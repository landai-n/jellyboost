package dev.jellyboost.player.bitrate

import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.player.api.PlayerApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures how much throughput this device actually has to the server, and turns it into the cap
 * `PlaybackQuality.AUTO` negotiates with (DECISIONS.md, 2026-08-15).
 *
 * Auto used to send no cap at all, which let the device profile's 120 Mbps ceiling stand and had the
 * server direct-play 60 Mbps remuxes over links that cannot carry them. A progressive direct play
 * has no renditions to adapt between, so the negotiated cap is the *only* adaptive point there is —
 * which is why this measurement exists at all, and why jellyfin-web's Auto does the same thing.
 *
 * The measurement itself is a ramp: [CHUNK_SIZES] bytes are fetched from `/Playback/BitrateTest` in
 * turn and each one is timed, stopping early as soon as one takes longer than [SLOW_CHUNK_MS] —
 * a slow link has already told us what we needed to know, and making it carry the next, larger
 * chunk only costs the user their first frame. The whole thing sits inside a [MEASUREMENT_BUDGET_MS]
 * budget, because a measurement that outlives the user's patience is worse than no measurement.
 *
 * The rate is **cumulative** — every byte fetched over every millisecond spent, including the chunk
 * that ended the ramp — and not the last chunk's own rate (DECISIONS.md, 2026-08-15 amendment).
 * A few megabytes are small enough to be answered largely out of TCP's congestion window and the
 * server's buffers, so the last chunk on its own measures a burst: on the user's link a 3 MB chunk
 * timed ~81 Mbps where a 30 MB pull sustained ~55. Counting the slow start and the earlier chunks
 * against the total is what turns the burst back into something the link can hold for a film.
 *
 * Single-flight and cached: the player asks on every open and on every re-negotiation, and the
 * answer does not change per item. A failed or cancelled measurement leaves the cache exactly as it
 * found it — a partial measurement is not evidence about the link, and remembering one would pin the
 * user to a number produced by the moment their coroutine was torn down.
 */
@Singleton
internal class AutoBitrateDetector(
    private val api: PlayerApi,
    private val preferences: AppPreferences,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
) {
    /**
     * The constructor the graph uses.
     *
     * Secondary rather than a defaulted parameter on the primary one: Hilt generates a call to the
     * `@Inject` constructor with every parameter supplied, so a `() -> Long` default would still
     * have to be satisfiable from the graph, and binding a clock into the graph to serve one test is
     * more machinery than the test is worth. The primary constructor stays open to the tests, which
     * drive [now] by hand so the ramp arithmetic can be asserted under `runTest`'s virtual time.
     */
    @Inject
    constructor(
        api: PlayerApi,
        preferences: AppPreferences,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(api, preferences, ioDispatcher, System::currentTimeMillis)

    /** Serialises measurements: concurrent callers wait for the one in flight and share its answer. */
    private val mutex = Mutex()

    @Volatile
    private var cached: Measurement? = null

    /**
     * The cap Auto should send, or `null` when nothing is known about this link.
     *
     * `null` is the degraded case and it is deliberately not an error: it reproduces exactly the
     * behaviour Auto had before this class existed — no cap, and the device profile's ceiling.
     */
    suspend fun currentCap(): Int? =
        mutex.withLock {
            val fresh = cached?.takeIf { now() - it.measuredAt < CACHE_TTL_MS }
            if (fresh != null) return@withLock fresh.bitrate

            val measured = measure()
            if (measured != null) {
                // Only a completed measurement is ever remembered, and it is remembered in both
                // places at once: in memory for this app run, and on disk as the prior the next
                // fresh start gets to use before it has measured anything of its own.
                cached = Measurement(bitrate = measured, measuredAt = now())
                runCatchingUnlessCancelled { preferences.setMaxStreamingBitrate(measured) }
                    .onFailure { Timber.w(it, "Could not persist the measured streaming ceiling") }
                return@withLock measured
            }

            // Failure order: what this run already knew, then what the last run left behind.
            cached?.bitrate ?: storedPrior()
        }

    /**
     * One ramped measurement, or `null` if it did not finish.
     *
     * Anything the call throws other than a cancellation is a failed measurement and nothing more:
     * this runs on the path to the first frame, and a server that will not answer a bitrate test is
     * not a reason to refuse to play the film.
     */
    private suspend fun measure(): Int? =
        withContext(ioDispatcher) {
            withTimeoutOrNull(MEASUREMENT_BUDGET_MS) {
                runCatchingUnlessCancelled { ramp() }
                    .onFailure { Timber.d(it, "Bitrate measurement failed; falling back to what is known") }
                    .getOrNull()
            }
        }

    /** Fetches the chunks in order and answers with the rate of everything that was fetched. */
    private suspend fun ramp(): Int {
        var totalBytes = 0L
        var totalElapsedMs = 0L
        for (size in CHUNK_SIZES) {
            val startedAt = now()
            val bytes = api.getBitrateTestBytes(size)
            val elapsedMs = now() - startedAt
            // The chunk that ends the ramp still counts, in both totals: it is the most recent — and
            // largest — evidence about the link, and dropping it would leave the answer to the very
            // chunks that had least time to leave TCP's slow start.
            totalBytes += bytes.size
            totalElapsedMs += elapsedMs
            if (elapsedMs > SLOW_CHUNK_MS) break
        }
        // At least a millisecond: a transfer that appears to arrive instantly is the clock's
        // resolution talking, not an infinitely fast link, and dividing by zero would say so.
        return capFor(byteCount = totalBytes, elapsedMs = totalElapsedMs.coerceAtLeast(1L))
    }

    /** The measured rate, with headroom taken off and clamped to something worth sending. */
    private fun capFor(
        byteCount: Long,
        elapsedMs: Long,
    ): Int {
        val bitsPerSecond = byteCount * BITS_PER_BYTE * MILLIS_PER_SECOND / elapsedMs
        return (bitsPerSecond * HEADROOM)
            .toLong()
            .coerceIn(MIN_CAP.toLong(), MAX_CAP.toLong())
            .toInt()
    }

    /** The last good measurement any run of the app made, or `null` if there has never been one. */
    private suspend fun storedPrior(): Int? =
        runCatchingUnlessCancelled { preferences.maxStreamingBitrate.first() }
            .onFailure { Timber.w(it, "Could not read the stored streaming ceiling") }
            .getOrNull()

    /** A completed measurement and when it was taken. */
    private data class Measurement(
        val bitrate: Int,
        val measuredAt: Long,
    )

    private companion object {
        /**
         * How long a measurement is believed, in milliseconds.
         *
         * Fifteen minutes is a compromise between the two failure modes: re-measuring per open costs
         * a round trip and some bytes on the very screen that must not be slow, while never
         * re-measuring would hold a film opened on Wi-Fi to the cap that mobile data earned it.
         * A quarter of an hour is longer than a track change or a quality tap and shorter than the
         * gap between two sittings.
         */
        const val CACHE_TTL_MS = 15 * 60 * 1_000L

        /**
         * The chunks fetched, in bytes, smallest first.
         *
         * Ramped rather than one big transfer: on a slow link the first chunk already answers the
         * question, and asking such a link for 3 MB before finding that out would spend the whole
         * budget on the measurement. Capped at 3 MB because the SDK materialises the response as a
         * `ByteArray` — the array is transient, but it is a real allocation on a device that is
         * about to hand its memory to a video decoder, and nothing larger buys accuracy.
         */
        val CHUNK_SIZES = listOf(500_000, 1_000_000, 3_000_000)

        /** A chunk slower than this ends the ramp: the link has already shown what it can do. */
        const val SLOW_CHUNK_MS = 1_000L

        /** The whole measurement's budget. Playback waits on this, so it is short on purpose. */
        const val MEASUREMENT_BUDGET_MS = 5_000L

        /**
         * What fraction of the measured rate is offered to the server.
         *
         * A stream negotiated at exactly the measured rate has nothing left for the protocol
         * overhead, the competing traffic on the link, or the moment the link dips — and every one
         * of those shows up to the user as a stall. Twenty per cent back is jellyfin-web's margin.
         */
        const val HEADROOM = 0.8

        /**
         * Floor, in bits per second — deliberately `PlaybackQuality.LOWEST`'s rung.
         *
         * A measured cap below the ladder's bottom rung would leave `PlaybackQuality.lowerThan`
         * with nothing to step down to, so the very first source error would go straight to
         * `FallbackDecision.GiveUp` instead of retrying. Clamping here keeps a genuinely terrible
         * link on the ladder rather than off the end of it.
         */
        const val MIN_CAP = 720_000

        /** Ceiling: the device profile's own `maxStreamingBitrate`. Above it the cap means nothing. */
        const val MAX_CAP = 120_000_000

        const val BITS_PER_BYTE = 8L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
