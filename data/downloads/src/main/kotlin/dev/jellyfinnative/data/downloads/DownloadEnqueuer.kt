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
import org.jellyfin.sdk.model.api.MediaStreamType
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
        private val seeder: SiblingSeeder,
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
                        val estimate = dto.sizeEstimate(quality)
                        val row =
                            dto.toDownloadRow(
                                userId = userId,
                                quality = quality,
                                now = now,
                                existing = existing,
                                position = existing?.queuePosition ?: nextPosition++,
                                estimate = estimate,
                                // The seed is read per row, after the ones before it were written,
                                // so the second episode of a season enqueued in one go is seeded
                                // from whatever finished *before* the tap — never from a sibling
                                // this same expansion queued and has not downloaded yet. That is
                                // why enqueue time is not the only moment seeding happens:
                                // `SiblingSeeder.seedPendingSiblingsOf` comes back to these rows
                                // as each episode lands (docs/features/download-quality.md).
                                projected = dto.siblingSeed(quality, estimate),
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
                // The row starts at zero downloaded but with the size the server reported, so the
                // queue tab can show a meaningful percentage before the first byte arrives.
                bytesDownloaded = existing?.bytesDownloaded ?: 0L,
                bytesTotal = estimate.bytes ?: existing?.bytesTotal ?: 0L,
                projectedBytes = projected,
                sizeIsExact = estimate.exact,
                queuePosition = position,
                directoryName = DownloadPaths.itemDirectoryName(this),
                itemName = name.orEmpty().ifBlank { id.toString() },
                seriesName = seriesName,
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )

        /**
         * How big the media file is expected to be, and whether that figure is the size the file
         * will *be* or merely a size it will not exceed.
         *
         * Three answers, in the order they are tried:
         *
         * 1. **[DownloadQuality.ORIGINAL]** — `mediaSources[0].size`, the file on disk, exact.
         * 2. **A stream copy** — see [remuxBytes]. The server will pass the video track through
         *    untouched, so the output is the source's video bytes plus one re-encoded AAC track:
         *    predictable, and marked exact.
         * 3. **A real transcode** — `runtime × min(cap, source bitrate)`, a deterministic upper
         *    bound and nothing more (DECISIONS.md, 2026-07-29). The bitrate is the *effective* one
         *    because a transcode can never need more bits per second than the source already
         *    carries; most sources sit well under a tier's cap, and estimating from the cap alone
         *    overstates the download by a large margin (a LOW episode estimated 552 MB and landed
         *    at 232 MB). When the source bitrate is missing or zero, the cap is the only number
         *    left.
         *
         * @return `bytes = null` when there is nothing at all to go on — a transcode of an item
         *   with no runtime. Reporting the *source's* size there would promise a number for a file
         *   the user is not going to receive.
         */
        private fun BaseItemDto.sizeEstimate(quality: DownloadQuality): SizeEstimate {
            val cap =
                quality.totalBitRate
                    ?: return SizeEstimate(mediaSources?.firstOrNull()?.size, exact = true)

            val ticks = runTimeTicks?.takeIf { it > 0L } ?: return SizeEstimate(null, exact = false)
            val seconds = ticks.toDouble() / TICKS_PER_SECOND

            remuxBytes(quality, seconds)?.let { return SizeEstimate(it, exact = true) }

            val sourceBitRate = mediaSources?.firstOrNull()?.bitrate?.takeIf { it > 0 }
            val bitRate = if (sourceBitRate != null) minOf(cap, sourceBitRate) else cap
            return SizeEstimate((seconds * bitRate / Byte.SIZE_BITS).toLong(), exact = false)
        }

        /**
         * The size of a transcode the server will answer by **copying** the video stream, or `null`
         * when this is not one.
         *
         * `DownloadUrlFactory.transcodedVideoUrl` sends `allowVideoStreamCopy=true`, which means the
         * server re-encodes only what it has to. When it copies the video track the output is
         * arithmetic rather than a guess: the source's own video bytes, plus the one AAC track we
         * always ask for at [DownloadQuality.AUDIO_BITRATE] (`allowAudioStreamCopy=false`, so audio
         * is re-encoded whatever the source was). Matroska's own overhead is well under a percent.
         *
         * ### The conditions, and how far they are verified
         * Checked against `EncodingHelper.CanStreamCopyVideo` in jellyfin `release-10.11.z` (the
         * method runs ~a dozen gates in sequence; any one failing forces a re-encode):
         * - **codec**: `SupportedVideoCodecs` is populated straight from our `videoCodec=h264`, and
         *   the test is a case-insensitive exact match against the source stream's `Codec`.
         * - **height**: fails on `Height > MaxHeight` **or on a null `Height`**, so an unknown
         *   height is a re-encode, not a free pass.
         * - **bitrate**: fails on `BitRate > VideoBitRate` **or on a null `BitRate`** (there is a
         *   `LiveStreamId` escape hatch, and a download has none). This is the trap worth knowing:
         *   plenty of MKVs carry no per-stream bitrate, and those transcode however small they are.
         *   Requiring the value to be present is therefore not merely conservative — it is the
         *   server's own rule, and it is why this deliberately does **not** fall back to deriving
         *   video bytes from the source's total size.
         * - **input container**: an `avi` source has a special case in the same method that can
         *   force a re-encode, so one is never claimed as a copy.
         *
         * The remaining gates (profile, level, bit depth, ref frames, HDR range, framerate, max
         * width, anamorphic, subtitle burn-in) are each enforced *only* when the matching query
         * parameter is present, and this client sends none of them; the interlacing gate needs a
         * `deInterlace` request we also never send. So for **our** URL these four checks are the
         * whole of it. Should the URL ever grow one of those parameters, this comment is the
         * warning that it also grows a gate.
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
         * What this item is likely to weigh, judged from episodes of the same show already on the
         * device at the same quality — or `null` when there is nothing to judge from.
         *
         * The arithmetic is [SiblingSeeder]'s, and it is shared on purpose: the same question is
         * asked again when a sibling finishes and when the queue starts a row, and three copies of
         * a median would be three chances for the wordings on one screen to disagree.
         *
         * What stays here is the *gate*. A row whose size is exact — an `ORIGINAL` download, or a
         * transcode the server will answer with a video stream copy — is not seeded at all: a guess
         * cannot improve on an arithmetic answer, and it would flip the row's wording from a plain
         * figure to a hedged one for nothing. Films get `null` too: there are no siblings, and a
         * director's other work is not evidence.
         */
        private suspend fun BaseItemDto.siblingSeed(
            quality: DownloadQuality,
            estimate: SizeEstimate,
        ): Long? {
            if (!quality.isTranscoded || estimate.exact) return null
            val ceiling = estimate.bytes?.takeIf { it > 0L } ?: return null
            val runtimeMillis = runTimeTicks?.div(TICKS_PER_MILLI)?.takeIf { it > 0L } ?: return null

            return seeder.seedFor(
                itemId = id,
                seriesName = seriesName,
                quality = quality,
                runtimeMillis = runtimeMillis,
                ceilingBytes = ceiling,
            )
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

            /** The same tick, per millisecond. */
            const val TICKS_PER_MILLI = 10_000L

            /** The one input container `CanStreamCopyVideo` has a special case for. */
            const val AVI_CONTAINER = "avi"
        }
    }

/**
 * The enqueue-time size prediction, and whether it is a figure or a ceiling.
 *
 * The two travel together because every caller needs both: the number goes in `bytesTotal` and the
 * flag in `sizeIsExact`, and computing one without the other is what would let a stream copy be
 * presented as *"up to"*.
 *
 * @property bytes the predicted size, or `null` when nothing could be predicted at all.
 * @property exact `true` when [bytes] is what the file will weigh (the server reported it, or the
 *   request will be answered with a video stream copy), `false` when it is an upper bound.
 */
internal data class SizeEstimate(
    val bytes: Long?,
    val exact: Boolean,
)
