package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What episodes of a show that already finished say the next one will weigh.
 *
 * The ceiling a transcoded row is enqueued with — `runtime × min(cap, source bitrate)` — is
 * deterministic and generous, and on easy content the encoder undershoots it by a wide margin.
 * Finished siblings are *measured*: a completed row's `bytesDownloaded` over its cached runtime is the
 * bitrate this server's encoder actually produced for this show at this quality. The **median** of
 * those rates, not the mean — one episode that happened to be a clip show should move the estimate,
 * not define it.
 *
 * The answer is wanted at three moments and the feature only works when all three ask: at enqueue,
 * when a sibling lands ([seedPendingSiblingsOf] — a season queued before anything of it finished), and
 * when the queue picks a row up.
 *
 * Two rules hold everywhere: a seed may only ever move the figure *down* from the ceiling the row was
 * enqueued with, and it never overwrites a projection that already exists — a live
 * `TranscodeSizeProjector` measurement outranks a guess made from other episodes.
 */
@Singleton
internal class SiblingSeeder
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val clock: Clock,
    ) {
        /**
         * @param itemId excluded from its own evidence: a re-enqueued row may still carry the bytes of
         *   an earlier attempt.
         * @param seriesName `null` (a film) is never seeded — a director's other work is not evidence.
         * @param ceilingBytes the enqueue-time upper bound the answer is clamped to.
         */
        @Suppress(
            "ReturnCount",
        )
        suspend fun seedFor(
            itemId: UUID,
            seriesName: String?,
            quality: DownloadQuality,
            runtimeMillis: Long,
            ceilingBytes: Long,
        ): Long? {
            if (!quality.isTranscoded || runtimeMillis <= 0L || ceilingBytes <= 0L) return null
            val series = seriesName?.takeIf { it.isNotBlank() } ?: return null
            val rate = observedRate(series, quality, excluding = itemId) ?: return null
            return rate.scaledTo(runtimeMillis, ceilingBytes)
        }

        /**
         * Seeds every row still waiting on the same show at the same quality, now that [completed] has
         * landed: a season queued in one batch has no finished sibling to learn from at enqueue, and
         * nothing else would come back to those rows once one arrived.
         *
         * Only `QUEUED`/`PAUSED` rows with no projection and no exact size are touched, and the write
         * re-checks that ([DownloadDao.setProjectedBytesIfAbsent]), so a row whose scanner has begun
         * measuring cannot be dragged back to a guess. `bytesTotal` is never touched: the ceiling is a
         * promise the enqueue step made.
         */
        @Suppress(
            "ReturnCount",
        )
        suspend fun seedPendingSiblingsOf(completed: DownloadEntity) {
            val series = completed.seriesName?.takeIf { it.isNotBlank() } ?: return
            if (!completed.quality.isTranscoded) return

            val pending = downloadDao.unseededSiblings(series, completed.quality)
            if (pending.isEmpty()) return

            // Read once for the whole batch: the rate is a property of the show and the quality,
            // and only the multiplication by each row's own runtime differs.
            val rate = observedRate(series, completed.quality, excluding = null) ?: return
            val runtimes = runtimeMillisOf(pending.map { it.itemId })
            val now = clock.instant()

            pending.forEach { row ->
                val runtimeMillis = runtimes[row.itemId] ?: return@forEach
                val ceiling = row.bytesTotal.takeIf { it > 0L } ?: return@forEach
                downloadDao.setProjectedBytesIfAbsent(row.itemId, rate.scaledTo(runtimeMillis, ceiling), now)
            }
            Timber.i("Seeded %d waiting %s downloads from a finished sibling", pending.size, series)
        }

        /**
         * The median bytes-per-millisecond this show's finished downloads at this quality landed at, or
         * `null` when none of them can be turned into a rate. A sibling whose item row is gone is
         * skipped rather than guessed at: without its runtime its size says nothing.
         */
        private suspend fun observedRate(
            seriesName: String,
            quality: DownloadQuality,
            excluding: UUID?,
        ): Double? {
            val siblings =
                downloadDao
                    .completedSiblings(seriesName, quality, SIBLING_SAMPLE)
                    .filter { it.itemId != excluding && it.bytesDownloaded > 0L }
            if (siblings.isEmpty()) return null

            val runtimes = runtimeMillisOf(siblings.map { it.itemId })
            val rates =
                siblings
                    .mapNotNull { row -> runtimes[row.itemId]?.let { row.bytesDownloaded.toDouble() / it } }
                    .sorted()
            return if (rates.isEmpty()) null else rates.median()
        }

        /** Runtimes in milliseconds for the items the cache still knows, keyed by id. */
        private suspend fun runtimeMillisOf(ids: List<UUID>): Map<UUID, Long> =
            itemDao
                .getItems(ids)
                .mapNotNull { entity ->
                    entity.runTimeTicks?.takeIf { it > 0L }?.let { entity.id to it / Ticks.PER_MILLISECOND }
                }.filter { (_, millis) -> millis > 0L }
                .toMap()

        /** A rate over a runtime, never beyond the ceiling the row was promised. */
        private fun Double.scaledTo(
            runtimeMillis: Long,
            ceilingBytes: Long,
        ): Long = (this * runtimeMillis).toLong().coerceIn(0L, ceilingBytes)

        private companion object {
            /** Newest first, so a show re-encoded at a new quality converges on its recent behaviour. */
            const val SIBLING_SAMPLE = 8
        }
    }

/** The middle rate, averaging the two middles for an even sample. Input must be sorted. */
private fun List<Double>.median(): Double =
    if (size % 2 == 1) this[size / 2] else (this[size / 2 - 1] + this[size / 2]) / 2.0
