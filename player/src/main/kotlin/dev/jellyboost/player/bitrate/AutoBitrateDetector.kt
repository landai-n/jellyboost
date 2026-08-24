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
 * Measures throughput to the server and turns it into the cap `PlaybackQuality.AUTO` negotiates.
 *
 * The rate is **cumulative** (all bytes over all elapsed ms), never the last chunk's own rate:
 * a few megabytes are answered largely out of TCP's congestion window, so the last chunk measures a
 * burst — a 3 MB chunk timed ~81 Mbps on a link where a 30 MB pull sustained ~55.
 *
 * A failed or cancelled measurement must leave the cache untouched: a partial measurement is not
 * evidence about the link.
 */
@Singleton
internal class AutoBitrateDetector(
    private val api: PlayerApi,
    private val preferences: AppPreferences,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
) {
    /** Secondary so the primary stays open to tests driving [now] by hand; Hilt supplies no clock. */
    @Inject
    constructor(
        api: PlayerApi,
        preferences: AppPreferences,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(api, preferences, ioDispatcher, System::currentTimeMillis)

    /** Single-flight: concurrent callers wait for the measurement in flight and share its answer. */
    private val mutex = Mutex()

    @Volatile
    private var cached: Measurement? = null

    /** The cap Auto should send. `null` is not an error: it means no cap, the device profile's ceiling. */
    suspend fun currentCap(): Int? =
        mutex.withLock {
            val fresh = cached?.takeIf { now() - it.measuredAt < CACHE_TTL_MS }
            if (fresh != null) return@withLock fresh.bitrate

            val measured = measure()
            if (measured != null) {
                cached = Measurement(bitrate = measured, measuredAt = now())
                runCatchingUnlessCancelled { preferences.setMaxStreamingBitrate(measured) }
                    .onFailure { Timber.w(it, "Could not persist the measured streaming ceiling") }
                return@withLock measured
            }

            cached?.bitrate ?: storedPrior()
        }

    /** Runs on the path to the first frame: any non-cancellation throw is just a failed measurement. */
    private suspend fun measure(): Int? =
        withContext(ioDispatcher) {
            withTimeoutOrNull(MEASUREMENT_BUDGET_MS) {
                runCatchingUnlessCancelled { ramp() }
                    .onFailure { Timber.d(it, "Bitrate measurement failed; falling back to what is known") }
                    .getOrNull()
            }
        }

    private suspend fun ramp(): Int {
        var totalBytes = 0L
        var totalElapsedMs = 0L
        for (size in CHUNK_SIZES) {
            val startedAt = now()
            val bytes = api.getBitrateTestBytes(size)
            val elapsedMs = now() - startedAt
            // The chunk that ends the ramp counts in both totals — it is the largest evidence there is.
            totalBytes += bytes.size
            totalElapsedMs += elapsedMs
            if (elapsedMs > SLOW_CHUNK_MS) break
        }
        // At least 1 ms: an apparently instant transfer is clock resolution, and 0 would divide badly.
        return capFor(byteCount = totalBytes, elapsedMs = totalElapsedMs.coerceAtLeast(1L))
    }

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

    private suspend fun storedPrior(): Int? =
        runCatchingUnlessCancelled { preferences.maxStreamingBitrate.first() }
            .onFailure { Timber.w(it, "Could not read the stored streaming ceiling") }
            .getOrNull()

    private data class Measurement(
        val bitrate: Int,
        val measuredAt: Long,
    )

    private companion object {
        /** 15 min: longer than a track change or quality tap, shorter than the gap between sittings. */
        const val CACHE_TTL_MS = 15 * 60 * 1_000L

        /** Capped at 3 MB: the SDK materialises the response as a `ByteArray`, and more buys no accuracy. */
        val CHUNK_SIZES = listOf(500_000, 1_000_000, 3_000_000)

        const val SLOW_CHUNK_MS = 1_000L

        /** Playback waits on this, so it is short on purpose. */
        const val MEASUREMENT_BUDGET_MS = 5_000L

        /** 20% back for protocol overhead, competing traffic and dips — jellyfin-web's margin. */
        const val HEADROOM = 0.8

        /**
         * Floor, bits per second — must stay at `PlaybackQuality.LOWEST`'s rung: a cap below the
         * ladder's bottom leaves `lowerThan` nothing to step to, so the first source error gives up.
         */
        const val MIN_CAP = 720_000

        /** Ceiling: the device profile's own `maxStreamingBitrate`. Above it the cap means nothing. */
        const val MAX_CAP = 120_000_000

        const val BITS_PER_BYTE = 8L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
