package dev.jellyboost.data.downloads.offline

import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.engine.MatroskaSeekIndexRepair
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers one question for the player: *is this item playable from this device right now, and out
 * of which files?*
 *
 * A `null` answer is the normal case for anything that was never downloaded, and it is also the
 * answer for a download that is not finished, or one whose media file has gone missing from disk —
 * both of which must fall back to streaming rather than fail. The check is deliberately made
 * against the filesystem and not only against Room: a user who clears the app's external storage
 * leaves the rows behind, and a `file://` URI pointing at nothing produces an ExoPlayer source
 * error several seconds into an otherwise silent screen.
 *
 * Optional files are filtered the same way, one by one: a subtitle track whose sidecar failed to
 * download simply is not offered, which is what the plan means by "optional-file failure → item
 * still playable".
 *
 * It is also where a transcoded download is made **seekable**. Being the one gate every offline
 * playback passes through makes it the only place a repair reaches downloads that were already on
 * the device when it shipped — see [MatroskaSeekIndexRepair] for what is wrong with those files and
 * why fixing them here rather than in the download pipeline was the point.
 */
@Singleton
class DownloadedMediaProvider
    @Inject
    internal constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val itemMapper: ItemEntityMapper,
        private val seekIndex: MatroskaSeekIndexRepair,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * @return what is on disk for [itemId], or `null` when the item cannot be played locally.
         */
        suspend fun get(itemId: UUID): DownloadedMedia? =
            withContext(ioDispatcher) {
                val stored = downloadDao.getWithFiles(itemId)
                if (stored == null || stored.download.status != DownloadStatus.DOWNLOADED) {
                    return@withContext null
                }

                val mediaFile = stored.files.firstOrNull { it.type == DownloadFileType.MEDIA }?.takeIfOnDisk()
                if (mediaFile == null) {
                    Timber.w("Download %s is complete but its media file is missing from disk", itemId)
                    return@withContext null
                }

                // The blob is the only place the streams live; without it there are no track lists
                // and the item is better streamed than played as a single untitled track.
                val dto = itemDao.getItem(itemId)?.let(itemMapper::toDtoOrNull)
                val mediaSource = dto?.pickMediaSource(stored.download.mediaSourceId)
                val runTimeTicks = mediaSource?.runTimeTicks ?: dto?.runTimeTicks ?: 0L

                // Idempotent, and a no-op in two reads for anything that already has a seek index —
                // which after the first play of a given file is everything.
                seekIndex.ensureSeekable(File(mediaFile.path), Ticks.ticksToMillis(runTimeTicks))

                DownloadedMedia(
                    itemId = itemId,
                    mediaSourceId =
                        mediaSource?.id
                            ?: stored.download.mediaSourceId
                            ?: itemId.toString(),
                    mediaSource = mediaSource,
                    mediaUri = localFileUri(mediaFile.path),
                    runTimeTicks = runTimeTicks,
                    // Carried through because the cached blob describes the *source* file: at any
                    // transcoded step the bytes on disk hold one audio track and no embedded
                    // subtitles, and only this row remembers that.
                    quality = stored.download.quality,
                    // …and which audio track that was. `null` on a row written before schema v8,
                    // which is what makes the resolver's legacy fallback a real code path rather
                    // than dead defensiveness.
                    bakedAudioStreamIndex = stored.download.bakedAudioStreamIndex,
                    subtitles = stored.files.toSubtitles(),
                    // No seek-repair call here (unlike [mediaFile] above): these are locally muxed
                    // by the post-fetch strip and land with a complete `moov`, not a server-fetched
                    // transcode that arrives without one.
                    audio = stored.files.toAudio(),
                    trickplay = stored.files.toTrickplay(dto),
                )
            }

        /**
         * The media source the file on disk actually came from.
         *
         * Matched dash-insensitively for the same reason `PlaybackInfoResolver` does it: the id the
         * download row stored came off a `PlaybackInfo`-shaped response and may be dash-less, while
         * the cached item's own sources carry dashes.
         */
        private fun BaseItemDto.pickMediaSource(mediaSourceId: String?): MediaSourceInfo? {
            val sources = mediaSources.orEmpty()
            val wanted = mediaSourceId?.replace("-", "")
            return sources.firstOrNull { it.id?.replace("-", "") == wanted } ?: sources.firstOrNull()
        }

        private fun List<DownloadFileEntity>.toSubtitles(): List<DownloadedSubtitle> =
            filter { it.type == DownloadFileType.SUBTITLE }
                .mapNotNull { file ->
                    val index = file.streamIndex ?: return@mapNotNull null
                    val onDisk = file.takeIfOnDisk() ?: return@mapNotNull null
                    DownloadedSubtitle(streamIndex = index, uri = localFileUri(onDisk.path))
                }

        /**
         * The downloaded extra audio tracks of a transcoded download, sorted ascending by stream
         * index — the order [DownloadedMedia.audio] promises the player.
         */
        private fun List<DownloadFileEntity>.toAudio(): List<DownloadedAudio> =
            filter { it.type == DownloadFileType.AUDIO }
                .mapNotNull { file ->
                    val index = file.streamIndex ?: return@mapNotNull null
                    val onDisk = file.takeIfOnDisk() ?: return@mapNotNull null
                    DownloadedAudio(streamIndex = index, uri = localFileUri(onDisk.path))
                }.sortedBy { it.streamIndex }

        /**
         * The downloaded tile sheets, paired with the geometry that makes them addressable.
         *
         * The planner only ever fetches one resolution — the largest the server generated — so the
         * sheets on disk agree on their `tileWidth`; `maxOfOrNull` is there for the case where an
         * older build left tiles of a second resolution behind, which would otherwise interleave
         * two grids into one nonsensical strip.
         */
        @Suppress(
            // Trickplay needs four facts on disk; a missing one means no trickplay, not a partial result.
            "ReturnCount",
        )
        private fun List<DownloadFileEntity>.toTrickplay(dto: BaseItemDto?): DownloadedTrickplay? {
            val tiles = filter { it.type == DownloadFileType.TRICKPLAY_TILE && it.tileWidth != null }
            val width = tiles.mapNotNull { it.tileWidth }.maxOrNull() ?: return null
            val info = dto?.trickplayInfo(width) ?: return null

            val uris =
                tiles
                    .filter { it.tileWidth == width }
                    .sortedBy { it.tileIndex ?: 0 }
                    .mapNotNull { file -> file.takeIfOnDisk()?.let { localFileUri(it.path) } }
            if (uris.isEmpty()) return null

            return DownloadedTrickplay(
                width = info.width,
                height = info.height,
                tileWidth = info.tileWidth,
                tileHeight = info.tileHeight,
                thumbnailCount = info.thumbnailCount,
                intervalMs = info.interval,
                tileUris = uris,
            )
        }

        /** The server's trickplay description for one thumbnail width, across every media source. */
        private fun BaseItemDto.trickplayInfo(width: Int): TrickplayInfoDto? =
            trickplay
                ?.values
                ?.flatMap { byWidth -> byWidth.values }
                ?.firstOrNull { it.width == width }

        /** The row, but only when its bytes are both complete and still there. */
        private fun DownloadFileEntity.takeIfOnDisk(): DownloadFileEntity? =
            takeIf { it.status == DownloadStatus.DOWNLOADED && File(it.path).isFile }
    }
