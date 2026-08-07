package dev.jellyboost.player.trickplay

import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.api.StreamUrlFactory
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.model.TrickplayTiles
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Finds the scrubbing thumbnails for whatever is playing (docs/PLAN.md, "M9 Polish" → trickplay
 * scrubber).
 *
 * Both halves of the sealed [PlaybackMediaSource] end at the same [TrickplayTiles], so the scrubber
 * neither knows nor cares whether it is drawing sheets off the SD card or off the server:
 *
 * - a **downloaded** item already carries its sheets — M7 fetched them, M8 made them reachable, and
 *   there is nothing to ask anyone;
 * - a **streamed** item has geometry but no URLs, so the item is re-read for its `trickplay` map and
 *   the sheet URLs are derived from it.
 *
 * Absence is a first-class answer. A server that generated no thumbnails, an unreachable one, and an
 * item whose geometry is nonsense all return `null`, and the seek bar simply has no preview above
 * it — the plan's "graceful absence, no placeholder flicker".
 */
@Singleton
internal class TrickplayResolver
    @Inject
    constructor(
        private val api: PlayerApi,
        private val urls: StreamUrlFactory,
    ) {
        /**
         * @param preferredWidth the thumbnail width the UI would like. The server holds one set of
         *   sheets per width and rarely the one asked for, so the closest available is used.
         * @return the sheets to scrub with, or `null` when this item has none.
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun resolve(
            source: PlaybackMediaSource,
            preferredWidth: Int = DEFAULT_PREFERRED_WIDTH,
        ): TrickplayTiles? =
            when (source) {
                // No network, ever: this is the path an airplane-mode session takes.
                is LocalPlaybackMediaSource -> source.trickplay?.toTiles()?.takeIf { it.isUsable }

                is RemotePlaybackMediaSource ->
                    try {
                        remoteTiles(source, preferredWidth)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // A server without trickplay is the normal case, not an incident.
                        Timber.d(error, "No trickplay available for %s", source.itemId)
                        null
                    }
            }

        private suspend fun remoteTiles(
            source: RemotePlaybackMediaSource,
            preferredWidth: Int,
        ): TrickplayTiles? {
            val info = api.getTrickplayInfo(source.itemId).pickFor(source.mediaSourceId, preferredWidth) ?: return null
            val perSheet = info.tileWidth * info.tileHeight
            if (perSheet <= 0 || info.thumbnailCount <= 0 || info.interval <= 0) return null

            // Derived, never served: the server reports how many thumbnails exist and how many fit
            // on a sheet, and the sheet count follows — the same arithmetic the download planner does.
            val sheetCount = (info.thumbnailCount + perSheet - 1) / perSheet
            return TrickplayTiles(
                thumbnailWidth = info.width,
                thumbnailHeight = info.height,
                columns = info.tileWidth,
                rows = info.tileHeight,
                thumbnailCount = info.thumbnailCount,
                intervalMs = info.interval,
                tileUris =
                    (0 until sheetCount).map { index ->
                        urls.trickplayTileUrl(
                            itemId = source.itemId,
                            width = info.width,
                            tileIndex = index,
                            mediaSourceId = source.mediaSourceId,
                        )
                    },
            )
        }

        /**
         * The geometry for this media source at the closest available width.
         *
         * The map is keyed by media source id and then by width. An item with several files holds
         * one entry per file, and the sheets of *another* file would be the wrong film — so the
         * requested source is preferred, with the dash-less spelling of an id accepted too because
         * that is what the server answers `PlaybackInfo` with. When the source is not in the map at
         * all (an older server, or a single-source item keyed by something else), every entry is
         * considered rather than giving up: a wrong-file preview is impossible when there is only
         * one file, and that is the case this fallback covers.
         */
        private fun Map<String, Map<String, TrickplayInfoDto>>.pickFor(
            mediaSourceId: String,
            preferredWidth: Int,
        ): TrickplayInfoDto? {
            val wanted = mediaSourceId.replace("-", "")
            val forSource = entries.firstOrNull { it.key.replace("-", "") == wanted }?.value?.values
            val candidates = forSource?.takeIf { it.isNotEmpty() } ?: values.flatMap { it.values }
            return candidates.minByOrNull { abs(it.width - preferredWidth) }
        }

        private companion object {
            /**
             * The thumbnail width the scrubber asks for.
             *
             * Jellyfin's own default sheet is 320 px wide and the preview is drawn at roughly a
             * sixth of a tablet's width, so anything larger is bytes the user waits for and never
             * sees. The closest available width wins, so this is a preference and not a
             * requirement.
             */
            const val DEFAULT_PREFERRED_WIDTH = 320
        }
    }

/** `true` when the geometry can actually address a thumbnail. */
private val TrickplayTiles.isUsable: Boolean
    get() = tileUris.isNotEmpty() && intervalMs > 0 && columns * rows > 0 && thumbnailCount > 0
