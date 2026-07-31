package dev.jellyboost.data.downloads.engine

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
 * What episodes of a show that already finished say the next one will weigh (schema v6).
 *
 * The ceiling a transcoded row is enqueued with — `runtime × min(cap, source bitrate)` — is
 * deterministic and generous, and on easy content the encoder undershoots it by a wide margin.
 * Finished siblings are *measured*: a completed row's `bytesDownloaded` is the real file, the item
 * cache still holds its runtime, so `bytes / runtime` is the bitrate this server's encoder actually
 * produced for this show at this quality. The **median** of those rates, not the mean: one episode
 * that happened to be a clip show should move the estimate, not define it.
 *
 * This lives in its own class because the answer is wanted at three different moments, and the
 * feature only works when all three ask (docs/features/download-quality.md, "Sibling seeding"):
 *
 * 1. **At enqueue** (`DownloadEnqueuer`) — the episode tapped after its siblings finished.
 * 2. **When a sibling lands** ([seedPendingSiblingsOf]) — the rest of a season queued *before*
 *    anything of it had finished. Without this a season enqueued in one go stays on "up to X" for
 *    every one of its rows however many episodes complete, which is the shape the user reported.
 * 3. **When the queue picks a row up** (`DownloadQueue`) — the belt to (2)'s braces, for a row
 *    enqueued while its siblings were still downloading on another device, or seeded before the
 *    cache knew its runtime.
 *
 * Two rules hold everywhere: a seed may only ever move the figure *down* from the ceiling the row
 * was enqueued with, and it never overwrites a projection that already exists — a live
 * `TranscodeSizeProjector` measurement outranks a guess made from other episodes, and re-seeding is
 * additive or it is nothing.
 */
@Singleton
class SiblingSeeder
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val clock: Clock,
    ) {
        /**
         * The size finished siblings suggest for one item, or `null` when there is nothing to judge
         * from.
         *
         * @param itemId the item being seeded — excluded from its own evidence, since a re-enqueued
         *   row may still carry the bytes of an earlier attempt.
         * @param seriesName the show, as the download row denormalises it; `null` (a film) is never
         *   seeded, because a director's other work is not evidence.
         * @param ceilingBytes the enqueue-time upper bound the answer is clamped to.
         * @return the projection, in bytes, or `null`.
         */
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
         * Seeds every row still waiting on the same show at the same quality, now that [completed]
         * has landed.
         *
         * This is the half the shipped feature was missing. Seeding used to happen only inside the
         * enqueue transaction, so a season queued in one batch had no finished sibling to learn
         * from — and nothing ever came back to the rows once one arrived. Every episode after the
         * first therefore kept its "up to X" wording for the whole of the download, which is
         * precisely the case the seed exists for.
         *
         * Only `QUEUED` and `PAUSED` rows that still carry no projection and whose size is not
         * already exact are touched, and the write itself re-checks that the projection is still
         * absent ([DownloadDao.setProjectedBytesIfAbsent]) — so a row the queue started, and whose
         * scanner has begun measuring, cannot be dragged back to a guess. `bytesTotal` is never
         * touched: the ceiling is a promise the enqueue step made.
         */
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
         * The median bytes-per-millisecond this show's finished downloads at this quality actually
         * landed at, or `null` when none of them can be turned into a rate.
         *
         * A sibling whose item row is gone (a wiped cache) is skipped rather than guessed at: without
         * its runtime its size says nothing about how long it took to say it.
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
                    entity.runTimeTicks?.takeIf { it > 0L }?.let { entity.id to it / TICKS_PER_MILLI }
                }.filter { (_, millis) -> millis > 0L }
                .toMap()

        /** A rate over a runtime, never beyond the ceiling the row was promised. */
        private fun Double.scaledTo(
            runtimeMillis: Long,
            ceilingBytes: Long,
        ): Long = (this * runtimeMillis).toLong().coerceIn(0L, ceilingBytes)

        private companion object {
            /** A `runTimeTicks` tick is 100 ns, so there are ten thousand of them in a millisecond. */
            const val TICKS_PER_MILLI = 10_000L

            /**
             * How many finished siblings the seed is taken from.
             *
             * Newest first, so a show re-encoded at a new quality converges on its recent
             * behaviour; small enough that the extra `getItems` stays one cheap query.
             */
            const val SIBLING_SAMPLE = 8
        }
    }

/** The middle rate, averaging the two middles for an even sample. Input must be sorted. */
private fun List<Double>.median(): Double =
    if (size % 2 == 1) this[size / 2] else (this[size / 2 - 1] + this[size / 2]) / 2.0
