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
 * Assembles the Cast framework's `MediaQueueItem` from a [CastMediaSpec].
 *
 * Media3's `DefaultMediaItemConverter` cannot be used: it copies a `MediaItem`'s URI, MIME type and
 * a little metadata and **ignores `subtitleConfigurations` entirely**, so every side-loaded subtitle
 * would be dropped on the way to the receiver — which is most of what casting a Jellyfin item
 * involves.
 *
 * Deliberately free of decisions. Everything worth getting wrong — the URL, its credentials, the
 * content type, the track ids — was settled by `CastSpecMapper` in plain data; `MediaInfo` and
 * `MediaTrack` cannot be built off a device, so what is left here is the part no test could cover
 * anyway.
 *
 * ### How the spec gets here
 * Media3 hands a converter a `MediaItem` and nothing else, so the spec travels *inside* the item as
 * `localConfiguration.tag` — see [toMediaItem], which is the only place such an item is built. A
 * `MediaItem` that arrives without one (nothing in this app produces one, but the framework may
 * round-trip its own) still yields a playable item from the URI alone rather than throwing inside a
 * Cast callback.
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

        /**
         * The reverse direction, used when the framework describes a queue back to the player.
         *
         * There is nothing to recover here — the spec was consumed on the way out — so this rebuilds
         * only what `RemoteCastPlayer` reads off the item: its id and its URI.
         */
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

        /**
         * The track id is the Jellyfin stream index, unchanged — that identity is what lets
         * `CastPlayerHandle.selectSubtitleTrack` pass the picker's index straight to
         * `RemoteMediaClient.setActiveMediaTracks`.
         */
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

/**
 * The [CastMediaSpec] as a `MediaItem`, which is the only shape `CastPlayer` accepts.
 *
 * The spec rides along as the item's tag because `MediaItemConverter` is handed nothing else, and
 * because the alternative — re-deriving `MediaInfo` from the `MediaItem`'s own fields — would put
 * the subtitle URLs, the track ids and the metadata back into a form Media3 does not carry. Read
 * back by [CastMediaItemConverter.toMediaQueueItem]; nothing else reads it.
 */
@UnstableApi
internal fun CastMediaSpec.toMediaItem(): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(mediaId)
        .setUri(contentId.toUri())
        .setMimeType(contentType)
        .setTag(this)
        .build()
