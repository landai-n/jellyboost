package dev.jellyboost.data.downloads.impl

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadApi
import dev.jellyboost.data.downloads.engine.SiblingSeeder
import dev.jellyboost.data.downloads.plan.DownloadPaths
import dev.jellyboost.data.downloads.plan.downloadAudioStreamIndex
import dev.jellyboost.data.downloads.plan.isFolderItem
import dev.jellyboost.data.mapper.toItemType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "the user tapped Download" into rows Room can hand to the queue.
 *
 * The metadata write and the queue row are all-or-nothing: files on disk that no screen can show are
 * worse than no download. `ItemEntity(source = DOWNLOAD)` is what the offline screens read, and a
 * later lean browse write-through is forbidden from demoting it.
 *
 * A folder — season, series, album, artist, playlist — has no file to fetch and the server answers
 * `400`, so containers are expanded here, the one place the rule lives.
 */
@Singleton
internal class DownloadEnqueuer
    @Suppress(
        "LongParameterList",
    )
    @Inject
    constructor(
        private val api: DownloadApi,
        private val itemDao: ItemDao,
        private val downloadDao: DownloadDao,
        private val deleter: DownloadDeleter,
        private val mapper: ItemEntityMapper,
        private val appPreferences: AppPreferences,
        private val seeder: SiblingSeeder,
        private val transactionRunner: TransactionRunner,
        private val clock: Clock,
    ) {
        /**
         * Enqueues one item, or — for a container — every child under it. An empty success list means
         * everything under it is already on the device.
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
         * A container's children, in the server's order (broadcast order, so a queue drained
         * top-to-bottom plays in watch order), minus the ones already spoken for. The container's own
         * download row is deleted first: a row keyed on a folder can only ever fail, and no retry
         * moves one.
         */
        @Suppress(
            "ReturnCount",
        )
        private suspend fun enqueueContainer(
            container: BaseItemDto,
            userId: UUID,
        ): AppResult<List<DownloadEntity>> {
            val episodeIds =
                when (val result = childItemIds(container)) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value
                }
            if (episodeIds.isEmpty()) {
                Timber.w("Nothing to download under %s (%s)", container.name, container.type)
                return AppResult.Failure(AppError.NotFound(container.id.toString()))
            }

            removeDoomedContainerRow(container)

            // One read for the whole container, not one per child. An id with no row is absent from
            // the answer, which `isRetryable` already reads as "yes".
            val existing = downloadDao.getAll(episodeIds).associateBy { it.itemId }
            val pending = episodeIds.filter { existing[it].isRetryable() }
            if (pending.isEmpty()) return AppResult.Success(emptyList())

            val fetched =
                when (val result = api.getFullItems(pending)) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value.associateBy { it.id }
                }
            // `getItems(ids = …)` answers in its own order; the queue's is the order that was asked for.
            val episodes = pending.mapNotNull(fetched::get)
            if (episodes.isEmpty()) return AppResult.Failure(AppError.NotFound(container.id.toString()))

            val known = episodes.map { it.id }.toSet() + container.id
            val parents = fetchParents(episodes, exclude = known)
            // A playlist is not cached alongside what it expanded to: Room has no playlist-membership
            // relation, so the offline row would be a playlist with a permanently empty track list.
            val cached = if (container.type == BaseItemKind.PLAYLIST) emptyList() else listOf(container)
            return write(userId, cache = cached + episodes + parents, targets = episodes)
        }

        private suspend fun childItemIds(container: BaseItemDto): AppResult<List<UUID>> =
            when (container.type) {
                BaseItemKind.SERIES -> api.getEpisodeIds(seriesId = container.id, seasonId = null)
                BaseItemKind.SEASON -> {
                    val seriesId =
                        container.seriesId
                            ?: return AppResult.Failure(AppError.NotFound(container.id.toString()))
                    api.getEpisodeIds(seriesId = seriesId, seasonId = container.id)
                }

                // Each answers ids in the order the matching screen shows them.
                BaseItemKind.MUSIC_ALBUM -> api.getAlbumTrackIds(container.id)
                BaseItemKind.MUSIC_ARTIST -> api.getArtistTrackIds(container.id)
                BaseItemKind.PLAYLIST -> api.getPlaylistTrackIds(container.id)

                // The detail screen never offers Download on a box set or a library folder.
                else -> {
                    Timber.w("%s is a folder this pipeline cannot expand", container.type)
                    AppResult.Failure(AppError.Unknown())
                }
            }

        /**
         * Removes a download row keyed on the container itself — it can never finish, so leaving it
         * keeps a permanent failure on the Downloads screen.
         */
        private suspend fun removeDoomedContainerRow(container: BaseItemDto) {
            if (downloadDao.get(container.id) == null) return

            Timber.i("Removing the unusable download row of %s", container.name)
            @Suppress("TooGenericExceptionCaught")
            try {
                // `deleteUnlessRunnable` skips rows the queue can still reach, and a doomed
                // container row is usually QUEUED; claiming it first is what makes the delete take.
                downloadDao.demoteRunnable(listOf(container.id), DownloadStatus.CANCELLED, clock.instant())
                deleter.delete(container.id)
            } catch (error: Exception) {
                // Best effort: a stuck row must not stop the episodes the user asked for from queueing.
                Timber.w(error, "Could not remove the download row of %s", container.name)
            }
        }

        /**
         * The parents of the given items — series/season, album/album artist — best effort: a failure
         * only means the offline series or artist page is empty until the user next browses online,
         * since `ItemDao.albumsOfArtist` and `tracksOfAlbum` filter on `source = DOWNLOAD`.
         */
        private suspend fun fetchParents(
            items: List<BaseItemDto>,
            exclude: Set<UUID>,
        ): List<BaseItemDto> {
            val parentIds =
                items
                    .flatMap { it.parentIds() }
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

        /** `albumArtists`, not `artistItems`: that is the id `ItemEntityMapper` writes into `albumArtistId`. */
        private fun BaseItemDto.parentIds(): List<UUID> =
            listOfNotNull(seriesId, seasonId, albumId, albumArtists?.firstOrNull()?.id)

        /**
         * The one write: metadata for [cache], a queue row for [targets]. The quality preference is
         * read once and stamped on every row — never re-read while a transfer it describes is
         * half-written.
         *
         * One transaction, for three races: `DownloadDeleter.pruneOrphanedItems` landing between the
         * two upserts would delete metadata whose row is one statement away (the drain then fails
         * *permanently* with `MissingMetadataException`); `maxQueuePosition()` is read once and
         * counted from; and the per-row `downloadDao.get` is what its own write is guarded on.
         */
        private suspend fun write(
            userId: UUID,
            cache: List<BaseItemDto>,
            targets: List<BaseItemDto>,
        ): AppResult<List<DownloadEntity>> {
            val now = clock.instant()
            // Read before the transaction opens: a transaction is not the place to wait on DataStore.
            val quality = appPreferences.downloadQuality.first()

            return try {
                AppResult.Success(transactionRunner.inTransaction { writeRows(userId, cache, targets, quality, now) })
            } catch (cancellation: CancellationException) {
                // The enqueue runs in the caller's (ViewModel) scope: reporting a dead scope as
                // `AppError.Storage` puts "could not download" on a screen the user has already left.
                throw cancellation
            } catch (error: SQLiteException) {
                // Narrowed to Room's own failure: anything else escaping this block is a bug in this
                // class and should crash rather than surface as a swallowed "could not enqueue".
                Timber.e(error, "Could not enqueue %s", targets.firstOrNull()?.id)
                AppResult.Failure(AppError.Storage(error))
            }
        }

        private suspend fun writeRows(
            userId: UUID,
            cache: List<BaseItemDto>,
            targets: List<BaseItemDto>,
            quality: DownloadQuality,
            now: java.time.Instant,
        ): List<DownloadEntity> {
            // Straight to the DAO, not through `BrowseCacheWriter`: these DTOs carry
            // `DownloadApi.DOWNLOAD_FIELDS`, the rich blob later lean browse writes must not replace.
            itemDao.upsert(cache.distinctBy { it.id }.map { mapper.toEntity(it, ItemSource.DOWNLOAD, now) })

            // Counted here rather than re-read per row: `maxQueuePosition()` only moves once the
            // previous row is committed.
            var nextPosition = (downloadDao.maxQueuePosition() ?: 0) + 1

            return targets.mapNotNull { dto ->
                val existing = downloadDao.get(dto.id)
                // Inside the transaction, the only place this holds: without it a second tap would
                // rewrite a finished or in-flight row's quality and size from the current preference.
                if (!existing.isRetryable()) {
                    Timber.i("%s is already downloaded or in flight; leaving its row alone", dto.name)
                    return@mapNotNull null
                }
                // Per row, not per tap: a season's 4K episode can be worth transcoding while the SD one is not.
                val (rowQuality, estimate) = dto.planQuality(quality)
                val row =
                    dto.toDownloadRow(
                        userId = userId,
                        quality = rowQuality,
                        now = now,
                        existing = existing,
                        position = existing?.queuePosition ?: nextPosition++,
                        estimate = estimate,
                        // Read per row, so a season enqueued in one go seeds from what finished
                        // *before* the tap, never from a sibling this expansion has not fetched yet.
                        projected = dto.siblingSeed(rowQuality, estimate),
                    )
                downloadDao.upsert(row)
                row
            }
        }

        @Suppress("LongParameterList")
        private fun BaseItemDto.toDownloadRow(
            userId: UUID,
            quality: DownloadQuality,
            now: java.time.Instant,
            existing: DownloadEntity?,
            position: Int,
            estimate: SizeEstimate,
            projected: Long?,
        ): DownloadEntity =
            DownloadEntity(
                itemId = id,
                userId = userId,
                status = DownloadStatus.QUEUED,
                mediaSourceId = mediaSources?.firstOrNull()?.id,
                quality = quality,
                bytesDownloaded = existing?.bytesDownloaded ?: 0L,
                bytesTotal = estimate.bytes ?: existing?.bytesTotal ?: 0L,
                projectedBytes = projected,
                sizeIsExact = estimate.exact,
                // Only a transcode bakes one track in; a row [planQuality] downgraded to ORIGINAL
                // records no pin, and its file holds every audio track of the source.
                bakedAudioStreamIndex = downloadAudioStreamIndex.takeIf { quality.isTranscoded },
                queuePosition = position,
                directoryName = DownloadPaths.itemDirectoryName(this),
                itemName = name.orEmpty().ifBlank { id.toString() },
                itemType = type.toItemType(),
                seriesName = seriesName,
                albumName = album?.takeIf { it.isNotBlank() },
                // Blankness is tested per operand: a whitespace `albumArtist` is not an answer, and
                // testing the elvis result would let it swallow the credited artists for good.
                artistName =
                    albumArtist?.takeIf { it.isNotBlank() }
                        ?: artists?.joinToString(", ")?.takeIf { it.isNotBlank() },
                groupId = seriesId ?: albumId,
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )

        /**
         * The quality this row is actually written at, and the size that goes with it: a transcode
         * that would not save space is stamped [DownloadQuality.ORIGINAL] instead, which is better on
         * every axis the pipeline measures — exact size, resumable `Range` transfer, no server CPU.
         *
         * Both figures are [sizeEstimate] of the quality as it would be *stamped*, so the comparison
         * is between the two files actually on offer; an unknown figure leaves the preference alone.
         */
        @Suppress(
            "ReturnCount",
        )
        private fun BaseItemDto.planQuality(preferred: DownloadQuality): PlannedQuality {
            // Music is originals-only, and the row says so: every downstream rule — transcode URL,
            // size projector, the no-pause rule, the *Transcoded* marker — keys off this column.
            if (type == BaseItemKind.AUDIO) {
                return PlannedQuality(DownloadQuality.ORIGINAL, sizeEstimate(DownloadQuality.ORIGINAL))
            }

            val chosen = PlannedQuality(preferred, sizeEstimate(preferred))
            if (!preferred.isTranscoded) return chosen

            val transcodedBytes = chosen.estimate.bytes ?: return chosen
            val original = sizeEstimate(DownloadQuality.ORIGINAL)
            val originalBytes = original.bytes?.takeIf { it > 0L } ?: return chosen
            if (transcodedBytes < ORIGINAL_THRESHOLD * originalBytes) return chosen

            Timber.i(
                "%s: a %s transcode is estimated at %d bytes against an original of %d — downloading the original",
                name,
                preferred,
                transcodedBytes,
                originalBytes,
            )
            return PlannedQuality(DownloadQuality.ORIGINAL, original)
        }

        /**
         * How big the media file is expected to be, and whether that is the size it will *be* or one
         * it will not exceed: the server's own figure for `ORIGINAL`, [remuxBytes] for a stream copy,
         * otherwise `runtime × min(cap, source bitrate)` plus [extraAudioBytes].
         *
         * The *effective* bitrate, not the cap: a transcode never needs more bits than the source
         * carries, and estimating from the cap alone overstated a LOW episode at 552 MB that landed
         * at 232 MB.
         *
         * @return `bytes = null` when there is nothing to go on — promising the source's size for a
         *   file the user will not receive is worse than promising nothing.
         */
        @Suppress(
            "ReturnCount",
        )
        private fun BaseItemDto.sizeEstimate(quality: DownloadQuality): SizeEstimate {
            val cap =
                quality.totalBitRate
                    ?: return SizeEstimate(mediaSources?.firstOrNull()?.size, exact = true)

            val ticks = runTimeTicks?.takeIf { it > 0L } ?: return SizeEstimate(null, exact = false)
            val seconds = ticks.toDouble() / Ticks.PER_SECOND
            val sidecars = extraAudioBytes(seconds)

            remuxBytes(quality, seconds)?.let {
                // Exact only while the item is single-language: a sidecar is itself a transcode.
                return SizeEstimate(it + sidecars, exact = sidecars == 0L)
            }

            val sourceBitRate = mediaSources?.firstOrNull()?.bitrate?.takeIf { it > 0 }
            val bitRate = if (sourceBitRate != null) minOf(cap, sourceBitRate) else cap
            return SizeEstimate((seconds * bitRate / Byte.SIZE_BITS).toLong() + sidecars, exact = false)
        }

        /**
         * What this item's audio sidecars weigh together — around 165 MB for a two-hour film, far too
         * much to leave out of the figure the user agrees to. The junk video each one is *fetched*
         * through is excluded: the strip stage deletes it, so it is bandwidth, not bytes on disk.
         */
        private fun BaseItemDto.extraAudioBytes(runtimeSeconds: Double): Long {
            val streams = mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
            val extras = (streams.count { it.type == MediaStreamType.AUDIO } - 1).coerceAtLeast(0)
            if (extras == 0) return 0L
            return (extras * runtimeSeconds * DownloadQuality.AUDIO_BITRATE / Byte.SIZE_BITS).toLong()
        }

        /**
         * The size of a transcode the server will answer by **copying** the video stream, or `null`
         * when this is not one: the source's own video bytes plus the one AAC track we always ask for
         * (`allowAudioStreamCopy=false`). Matroska's overhead is well under a percent.
         *
         * The conditions are jellyfin `release-10.11.z`'s `EncodingHelper.CanStreamCopyVideo`: a null
         * `Height` or a null per-stream `BitRate` forces a re-encode — plenty of MKVs carry no
         * per-stream bitrate — so a missing value must never be derived from the source's total size,
         * and `avi` has a special case of its own. The remaining gates fire only when their query
         * parameter is present and this client sends none; adding one to the URL adds a gate here.
         */
        @Suppress("ReturnCount")
        private fun BaseItemDto.remuxBytes(
            quality: DownloadQuality,
            runtimeSeconds: Double,
        ): Long? {
            val source = mediaSources?.firstOrNull() ?: return null
            if (source.container.equals(AVI_CONTAINER, ignoreCase = true)) return null

            val video =
                source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
            if (!video.codec.equals(DownloadQuality.VIDEO_CODEC, ignoreCase = true)) return null

            val maxHeight = quality.maxHeight ?: return null
            val height = video.height ?: return null
            if (height > maxHeight) return null

            val videoCap = quality.videoBitRate ?: return null
            val videoBitRate = video.bitRate?.takeIf { it > 0 } ?: return null
            if (videoBitRate > videoCap) return null

            val bits = runtimeSeconds * (videoBitRate.toLong() + DownloadQuality.AUDIO_BITRATE)
            return (bits / Byte.SIZE_BITS).toLong()
        }

        /**
         * What this item is likely to weigh, judged from finished siblings of the same show at the
         * same quality — `null` when the size is already exact (a guess cannot improve on arithmetic)
         * or there are no siblings to judge from.
         */
        @Suppress(
            "ReturnCount",
        )
        private suspend fun BaseItemDto.siblingSeed(
            quality: DownloadQuality,
            estimate: SizeEstimate,
        ): Long? {
            if (!quality.isTranscoded || estimate.exact) return null
            val ceiling = estimate.bytes?.takeIf { it > 0L } ?: return null
            val runtimeMillis = Ticks.positiveMillisOrNull(runTimeTicks) ?: return null

            return seeder.seedFor(
                itemId = id,
                seriesName = seriesName,
                quality = quality,
                runtimeMillis = runtimeMillis,
                ceilingBytes = ceiling,
            )
        }

        /**
         * `true` when a tap should (re)queue this item: no row, `ERROR`, or `CANCELLED` — the status a
         * row holds between a cancel and its deletion. Re-queueing a `CANCELLED` row has to write: the
         * cascade arriving afterwards finds it runnable and leaves it alone.
         */
        private fun DownloadEntity?.isRetryable(): Boolean =
            this == null || status == DownloadStatus.ERROR || status == DownloadStatus.CANCELLED

        private companion object {
            /** The one input container `CanStreamCopyVideo` has a special case for. */
            const val AVI_CONTAINER = "avi"

            /**
             * `0.9`: a transcode saving less than about a tenth of the file is not a trade — it costs
             * a generation of re-encoding, server CPU, byte-level resume and an exact size. The margin
             * leans the right way too, since the transcoded figure compared is an upper bound.
             */
            const val ORIGINAL_THRESHOLD = 0.9
        }
    }

/**
 * The enqueue-time size prediction. [exact] is `true` when [bytes] is what the file will weigh — the
 * server reported it, or the request is answered with a video stream copy — and `false` for a ceiling.
 */
internal data class SizeEstimate(
    val bytes: Long?,
    val exact: Boolean,
)

/**
 * Quality and size travel as a pair: a row downgraded to [DownloadQuality.ORIGINAL] whose
 * `bytesTotal` was still the transcode's estimate would promise a size for a file it will not fetch.
 */
private data class PlannedQuality(
    val quality: DownloadQuality,
    val estimate: SizeEstimate,
)
