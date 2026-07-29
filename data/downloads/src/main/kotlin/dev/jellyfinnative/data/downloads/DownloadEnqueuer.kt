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
import org.jellyfin.sdk.model.api.BaseItemKind
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
 *
 * ### Containers expand
 * A season and a series are **folders**, and a folder has no file to fetch — asking the server for
 * one answers `400`. So when the item handed in is one, it is replaced by its episodes and every
 * one of them is enqueued exactly as a direct tap on that episode would have been: same re-fetch,
 * same quality preference, same paths (DECISIONS.md, 2026-07-29). That makes this class the one
 * place the rule lives, so no caller can reintroduce the bug by enqueuing a folder.
 */
@Singleton
class DownloadEnqueuer
    @Inject
    constructor(
        private val api: DownloadApi,
        private val itemDao: ItemDao,
        private val downloadDao: DownloadDao,
        private val deleter: DownloadDeleter,
        private val mapper: ItemEntityMapper,
        private val appPreferences: AppPreferences,
        private val clock: Clock,
    ) {
        /**
         * Enqueues one item, or — for a season or a series — every episode under it.
         *
         * @param userId owner of the download — the delete cascade needs it.
         * @return the rows created, in queue order, or a failure describing why nothing was written.
         *   An empty list means every episode of a container is already on the device, which is a
         *   success with nothing left to do.
         */
        suspend fun enqueue(
            itemId: UUID,
            userId: UUID,
        ): AppResult<List<DownloadEntity>> {
            val fetched =
                when (val result = api.getFullItems(listOf(itemId))) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value.firstOrNull()
                } ?: return AppResult.Failure(AppError.NotFound(itemId.toString()))

            return if (fetched.isFolderItem) enqueueContainer(fetched, userId) else enqueueSingle(fetched, userId)
        }

        /** A movie or an episode: itself, plus its parents for offline upward navigation. */
        private suspend fun enqueueSingle(
            item: BaseItemDto,
            userId: UUID,
        ): AppResult<List<DownloadEntity>> {
            val parents = fetchParents(listOf(item), exclude = setOf(item.id))
            return write(userId, cache = listOf(item) + parents, targets = listOf(item))
        }

        /**
         * A season or a series: its episodes, in order, minus the ones already on the device.
         *
         * Three rules, each with a failure mode behind it:
         *
         * - **The container's own row is deleted first.** Before this fix a tap on a season wrote a
         *   download row keyed on the *season*, which could only ever fail; those rows are still on
         *   users' devices and no retry will ever move them. A row for a folder is doomed by
         *   definition, so the cascade runs on it (files, rows, orphaned metadata) before the real
         *   downloads are queued.
         * - **Episodes already spoken for are skipped**, so re-tapping Download on a season the user
         *   half-downloaded does not restart it. `ERROR` is the exception: a failed episode is
         *   exactly what a second tap is meant to retry, and re-enqueueing keeps its queue position
         *   and the bytes already on disk.
         * - **Order is the server's**, which is broadcast order, so a queue drained top-to-bottom
         *   plays back in the order the user would watch.
         */
        private suspend fun enqueueContainer(
            container: BaseItemDto,
            userId: UUID,
        ): AppResult<List<DownloadEntity>> {
            val episodeIds =
                when (val result = childEpisodeIds(container)) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value
                }
            if (episodeIds.isEmpty()) {
                Timber.w("Nothing to download under %s (%s)", container.name, container.type)
                return AppResult.Failure(AppError.NotFound(container.id.toString()))
            }

            removeDoomedContainerRow(container)

            val pending = episodeIds.filter { downloadDao.get(it).isRetryable() }
            if (pending.isEmpty()) return AppResult.Success(emptyList())

            val fetched =
                when (val result = api.getFullItems(pending)) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value.associateBy { it.id }
                }
            // `getItems(ids = …)` answers in its own order; the queue's is the one that was asked
            // for, which is the order the user would watch them in.
            val episodes = pending.mapNotNull(fetched::get)
            if (episodes.isEmpty()) return AppResult.Failure(AppError.NotFound(container.id.toString()))

            val known = episodes.map { it.id }.toSet() + container.id
            val parents = fetchParents(episodes, exclude = known)
            return write(userId, cache = listOf(container) + episodes + parents, targets = episodes)
        }

        /** The ids under a container, or a failure when it is one this pipeline cannot expand. */
        private suspend fun childEpisodeIds(container: BaseItemDto): AppResult<List<UUID>> =
            when (container.type) {
                BaseItemKind.SERIES -> api.getEpisodeIds(seriesId = container.id, seasonId = null)
                BaseItemKind.SEASON -> {
                    val seriesId =
                        container.seriesId
                            ?: return AppResult.Failure(AppError.NotFound(container.id.toString()))
                    api.getEpisodeIds(seriesId = seriesId, seasonId = container.id)
                }

                // A box set or a library folder: the detail screen never offers Download on one, and
                // guessing what "download this library" means is not this milestone's business.
                else -> {
                    Timber.w("%s is a folder this pipeline cannot expand", container.type)
                    AppResult.Failure(AppError.Unknown())
                }
            }

        /**
         * Removes a download row keyed on the container itself, whatever state it is in.
         *
         * Such a row can never finish — that is the bug this fix is for — so leaving it would keep a
         * permanent failure on the Downloads screen next to the episodes that do work.
         */
        private suspend fun removeDoomedContainerRow(container: BaseItemDto) {
            if (downloadDao.get(container.id) == null) return

            Timber.i("Removing the unusable download row of %s", container.name)
            @Suppress("TooGenericExceptionCaught")
            try {
                deleter.delete(container.id)
            } catch (error: Exception) {
                // Best effort: a stuck row that could not be cleaned up must not stop the episodes
                // the user actually asked for from being queued.
                Timber.w(error, "Could not remove the download row of %s", container.name)
            }
        }

        /**
         * The series and season of the given items, best effort.
         *
         * A failure here is deliberately *not* fatal: the download itself is perfectly usable
         * without its parents cached, it only means the offline series page is missing until the
         * user next browses to it online.
         *
         * @param exclude ids already being cached by the caller — the item itself, and for an
         *   expanded container the container and its episodes.
         */
        private suspend fun fetchParents(
            items: List<BaseItemDto>,
            exclude: Set<UUID>,
        ): List<BaseItemDto> {
            val parentIds =
                items
                    .flatMap { listOfNotNull(it.seriesId, it.seasonId) }
                    .filterNot { it in exclude }
                    .distinct()
            if (parentIds.isEmpty()) return emptyList()

            return when (val result = api.getFullItems(parentIds)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> {
                    Timber.w("Could not cache the parents of %s: %s", items.first().id, result.error)
                    emptyList()
                }
            }
        }

        /**
         * The one write: metadata for everything in [cache], a queue row for everything in
         * [targets].
         *
         * The quality preference is read once, here, and stamped onto every row: the pipeline must
         * not re-read a preference the user can change while the transfer it describes is
         * half-written (DECISIONS.md, 2026-07-29). Enqueuing a whole season therefore fixes one
         * quality for the season, which is also the only answer a user would expect.
         */
        private suspend fun write(
            userId: UUID,
            cache: List<BaseItemDto>,
            targets: List<BaseItemDto>,
        ): AppResult<List<DownloadEntity>> {
            val now = clock.instant()
            val quality = appPreferences.downloadQuality.first()

            @Suppress("TooGenericExceptionCaught")
            return try {
                // The items and their parents in one upsert: a partially-cached hierarchy is the
                // state that makes offline navigation dead-end halfway up.
                //
                // Deliberately straight to the DAO and not through `BrowseCacheWriter`: these DTOs
                // came from `DownloadApi.DOWNLOAD_FIELDS`, so the blob written here is the rich one
                // every later lean browse write is forbidden from replacing.
                itemDao.upsert(cache.distinctBy { it.id }.map { mapper.toEntity(it, ItemSource.DOWNLOAD, now) })

                // Counted here rather than re-read per row: `maxQueuePosition()` only moves once the
                // previous row is committed, and a season enqueued in one go would otherwise pile
                // twenty episodes onto the same position.
                var nextPosition = (downloadDao.maxQueuePosition() ?: 0) + 1
                val rows =
                    targets.map { dto ->
                        val existing = downloadDao.get(dto.id)
                        val row =
                            dto.toDownloadRow(
                                userId = userId,
                                quality = quality,
                                now = now,
                                existing = existing,
                                position = existing?.queuePosition ?: nextPosition++,
                            )
                        downloadDao.upsert(row)
                        row
                    }
                AppResult.Success(rows)
            } catch (error: Exception) {
                Timber.e(error, "Could not enqueue %s", targets.firstOrNull()?.id)
                AppResult.Failure(AppError.Storage(error))
            }
        }

        private fun BaseItemDto.toDownloadRow(
            userId: UUID,
            quality: DownloadQuality,
            now: java.time.Instant,
            existing: DownloadEntity?,
            position: Int,
        ): DownloadEntity =
            DownloadEntity(
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

        /**
         * `true` when expanding a container should (re)queue this episode.
         *
         * No row at all, or a row that failed — anything else is already downloaded, downloading,
         * paused or waiting, and a second tap on the season must not disturb it.
         */
        private fun DownloadEntity?.isRetryable(): Boolean = this == null || status == DownloadStatus.ERROR

        private companion object {
            /** A `runTimeTicks` tick is 100 ns, so there are ten million of them in a second. */
            const val TICKS_PER_SECOND = 10_000_000.0
        }
    }
