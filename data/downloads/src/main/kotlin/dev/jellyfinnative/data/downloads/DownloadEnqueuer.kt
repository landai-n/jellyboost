package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.plan.DownloadPaths
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "the user tapped Download" into rows Room can hand to the queue (docs/PLAN.md, "Download
 * pipeline" → Enqueue).
 *
 * Four things happen, in this order, and the order is the point:
 *
 * 1. **A full re-fetch.** The item the user tapped came from a lean list request; the file plan
 *    needs `mediaSources`, `mediaStreams`, `trickplay` and the image tags, which only the full
 *    field set carries.
 * 2. **The parents too.** An episode's series and season are fetched and cached alongside it, so
 *    that offline the user can walk *up* from the downloaded episode to its show — the plan's
 *    "cached parents of downloaded items still open".
 * 3. **`ItemEntity(source = DOWNLOAD)`.** This is the row that makes the item appear in the offline
 *    home, library and search (M6's `OfflineJellyfinRepository` reads exactly this), and the row a
 *    later browse write-through is forbidden from demoting.
 * 4. **`DownloadEntity(QUEUED)`** at the end of the queue.
 *
 * Steps 1–3 are all-or-nothing: enqueueing a download whose metadata we failed to store would
 * produce files on disk that no screen can ever show.
 */
@Singleton
class DownloadEnqueuer
    @Inject
    constructor(
        private val api: DownloadApi,
        private val itemDao: ItemDao,
        private val downloadDao: DownloadDao,
        private val mapper: ItemEntityMapper,
        private val appPreferences: AppPreferences,
        private val clock: Clock,
    ) {
        /**
         * Enqueues one item.
         *
         * @param userId owner of the download — the delete cascade needs it.
         * @return the created row, or a failure describing why nothing was written.
         */
        suspend fun enqueue(
            itemId: UUID,
            userId: UUID,
        ): AppResult<DownloadEntity> {
            val fetched =
                when (val result = api.getFullItems(listOf(itemId))) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value.firstOrNull()
                } ?: return AppResult.Failure(AppError.NotFound(itemId.toString()))

            val parents = fetchParents(fetched)
            val now = clock.instant()
            // Read once, here, and stamped onto the row: the pipeline must not re-read a preference
            // the user can change while the transfer it describes is half-written (DECISIONS.md,
            // 2026-07-29).
            val quality = appPreferences.downloadQuality.first()

            @Suppress("TooGenericExceptionCaught")
            return try {
                // The item and its parents in one upsert: a partially-cached hierarchy is the state
                // that makes offline navigation dead-end halfway up.
                //
                // Deliberately straight to the DAO and not through `BrowseCacheWriter`: these DTOs
                // came from `DownloadApi.DOWNLOAD_FIELDS`, so the blob written here is the rich one
                // every later lean browse write is forbidden from replacing.
                itemDao.upsert((listOf(fetched) + parents).map { mapper.toEntity(it, ItemSource.DOWNLOAD, now) })

                val row = fetched.toDownloadRow(userId, quality, now)
                downloadDao.upsert(row)
                AppResult.Success(row)
            } catch (error: Exception) {
                Timber.e(error, "Could not enqueue %s", itemId)
                AppResult.Failure(AppError.Storage(error))
            }
        }

        /**
         * The series and season of an episode, best effort.
         *
         * A failure here is deliberately *not* fatal: the download itself is perfectly usable
         * without its parents cached, it only means the offline series page is missing until the
         * user next browses to it online.
         */
        private suspend fun fetchParents(item: BaseItemDto): List<BaseItemDto> {
            val parentIds = listOfNotNull(item.seriesId, item.seasonId).filter { it != item.id }.distinct()
            if (parentIds.isEmpty()) return emptyList()

            return when (val result = api.getFullItems(parentIds)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> {
                    Timber.w("Could not cache the parents of %s: %s", item.id, result.error)
                    emptyList()
                }
            }
        }

        private suspend fun BaseItemDto.toDownloadRow(
            userId: UUID,
            quality: DownloadQuality,
            now: java.time.Instant,
        ): DownloadEntity {
            val existing = downloadDao.get(id)
            val position = existing?.queuePosition ?: ((downloadDao.maxQueuePosition() ?: 0) + 1)

            return DownloadEntity(
                itemId = id,
                userId = userId,
                status = DownloadStatus.QUEUED,
                mediaSourceId = mediaSources?.firstOrNull()?.id,
                quality = quality,
                // The row starts at zero downloaded but with the size the server reported, so the
                // queue tab can show a meaningful percentage before the first byte arrives.
                bytesDownloaded = existing?.bytesDownloaded ?: 0L,
                bytesTotal = expectedBytes(quality) ?: existing?.bytesTotal ?: 0L,
                queuePosition = position,
                directoryName = DownloadPaths.itemDirectoryName(this),
                itemName = name.orEmpty().ifBlank { id.toString() },
                seriesName = seriesName,
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        }

        /**
         * How big the media file is expected to be, or `null` when there is nothing to go on.
         *
         * For [DownloadQuality.ORIGINAL] the server already knows: `mediaSources[0].size` is the
         * file on disk, exactly. For a transcode it does not — it has not encoded the file yet, and
         * it will not send a `Content-Length` either — so the size is `runtime × bitrate`, which is
         * within a few per cent for constrained H.264 and is the difference between a queue row
         * showing "43 % of ~4.2 GB" and an indeterminate bar for two hours (DECISIONS.md,
         * 2026-07-29).
         */
        private fun BaseItemDto.expectedBytes(quality: DownloadQuality): Long? {
            val bitRate = quality.totalBitRate ?: return mediaSources?.firstOrNull()?.size
            val ticks = runTimeTicks?.takeIf { it > 0L } ?: return null
            val seconds = ticks.toDouble() / TICKS_PER_SECOND
            return (seconds * bitRate / Byte.SIZE_BITS).toLong()
        }

        private companion object {
            /** A `runTimeTicks` tick is 100 ns, so there are ten million of them in a second. */
            const val TICKS_PER_SECOND = 10_000_000.0
        }
    }
