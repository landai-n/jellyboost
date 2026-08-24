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

/** Absence is a first-class answer: no thumbnails, an unreachable server and nonsense geometry all give `null`. */
@Singleton
internal class TrickplayResolver
    @Inject
    constructor(
        private val api: PlayerApi,
        private val urls: StreamUrlFactory,
    ) {
        /**
         * @param preferredWidth a preference only: the server holds one set of sheets per width, rarely the one
         *   asked for, so the closest available wins.
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

            // The server never reports a sheet count; it is ceil(thumbnails / per sheet).
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
         * Keyed by media source id, then by width. The dash-less spelling of an id is accepted because that is
         * what the server answers `PlaybackInfo` with; an unlisted source falls back to every entry, which can
         * only be the wrong file on a multi-file item an older server keyed differently.
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
            /** Jellyfin's own default sheet width; the preview is drawn at roughly a sixth of a tablet's width. */
            const val DEFAULT_PREFERRED_WIDTH = 320
        }
    }

private val TrickplayTiles.isUsable: Boolean
    get() = tileUris.isNotEmpty() && intervalMs > 0 && columns * rows > 0 && thumbnailCount > 0
