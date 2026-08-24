package dev.jellyboost.player.cast

import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3's `DefaultMediaItemConverter` cannot be used here: it ignores `subtitleConfigurations`
 * entirely, dropping every side-loaded subtitle on the way to the receiver.
 *
 * Media3 hands a converter a `MediaItem` and nothing else, so the spec travels inside it as
 * `localConfiguration.tag` (see [toMediaItem]). An item without one must still yield something
 * playable rather than throw inside a Cast callback.
 */
@Singleton
@UnstableApi
internal class CastMediaItemConverter
    @Inject
    constructor() : MediaItemConverter {
        override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
            val spec = mediaItem.localConfiguration?.tag as? CastMediaSpec
            val info = if (spec != null) mediaInfo(spec) else fallbackMediaInfo(mediaItem)
            return MediaQueueItem
                .Builder(info)
                .setAutoplay(true)
                .setStartTime(((spec?.startPositionMs ?: 0L).coerceAtLeast(0L)) / MILLIS_PER_SECOND)
                .build()
        }

        override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
            val info = mediaQueueItem.media
            val contentId = info?.contentUrl ?: info?.contentId.orEmpty()
            return MediaItem
                .Builder()
                .setMediaId(info?.contentId.orEmpty())
                .setUri(contentId.toUri())
                .setMimeType(info?.contentType)
                .build()
        }

        private fun mediaInfo(spec: CastMediaSpec): MediaInfo =
            MediaInfo
                .Builder(spec.contentId)
                .setContentUrl(spec.contentId)
                .setContentType(spec.contentType)
                .setStreamType(
                    when (spec.streamType) {
                        CastStreamType.Buffered -> MediaInfo.STREAM_TYPE_BUFFERED
                        CastStreamType.Live -> MediaInfo.STREAM_TYPE_LIVE
                    },
                ).setStreamDuration(spec.durationMs)
                .setMediaTracks(spec.tracks.map(::mediaTrack))
                .setMetadata(metadata(spec.metadata))
                .build()

        // Track id stays the Jellyfin stream index: `CastPlayerHandle.selectSubtitleTrack` passes the
        // picker's index straight to `RemoteMediaClient.setActiveMediaTracks`.
        private fun mediaTrack(track: CastTrackSpec): MediaTrack =
            MediaTrack
                .Builder(track.id.toLong(), MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(track.uri)
                .setContentType(track.mimeType)
                .setName(track.label)
                .setLanguage(track.language)
                .build()

        private fun metadata(metadata: CastMetadata): MediaMetadata =
            MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                metadata.title?.let { putString(MediaMetadata.KEY_TITLE, it) }
                metadata.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
                metadata.posterUrl?.let { addImage(WebImage(it.toUri())) }
            }

        private fun fallbackMediaInfo(mediaItem: MediaItem): MediaInfo {
            val configuration = mediaItem.localConfiguration
            val uri = configuration?.uri?.toString().orEmpty()
            return MediaInfo
                .Builder(uri)
                .setContentUrl(uri)
                .setContentType(configuration?.mimeType ?: DEFAULT_CONTENT_TYPE)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .build()
        }

        private companion object {
            const val MILLIS_PER_SECOND = 1000.0
            const val DEFAULT_CONTENT_TYPE = "video/mp4"
        }
    }

/** The tag is load-bearing: [CastMediaItemConverter.toMediaQueueItem] reads the spec back off it. */
@UnstableApi
internal fun CastMediaSpec.toMediaItem(): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(mediaId)
        .setUri(contentId.toUri())
        .setMimeType(contentType)
        .setTag(this)
        .build()
