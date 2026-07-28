package dev.jellyfinnative.player.resolve

import androidx.media3.common.MimeTypes
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.api.StreamUrlFactory
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import dev.jellyfinnative.player.model.SubtitleSpec
import dev.jellyfinnative.player.model.externalSubtitleTrackId
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a resolved media source into the URL (and side-loaded subtitles) ExoPlayer should open.
 *
 * The three delivery methods need three completely different URLs, and getting this table wrong
 * fails in ways that look like a decoder problem: a direct-play URL without `static=true` quietly
 * remuxes, and an HLS playlist opened without its MIME type is parsed as a progressive stream.
 *
 * Produces a plain [PlaybackMediaItemSpec] rather than a `MediaItem` so the decision can be unit
 * tested; `toMediaItem` performs the mechanical conversion on-device.
 */
@Singleton
class ExoMediaSourceFactory
    @Inject
    constructor(
        private val urls: StreamUrlFactory,
    ) {
        /**
         * @return what to hand ExoPlayer, or `null` when the source cannot be played at all —
         *   an unknown protocol, or a transcode offered over something other than HLS.
         */
        fun create(source: RemotePlaybackMediaSource): PlaybackMediaItemSpec? {
            val (uri, mimeType) =
                when (source.playMethod) {
                    PlayMethod.DIRECT_PLAY -> source.directPlayTarget() ?: return null
                    PlayMethod.DIRECT_STREAM -> source.directStreamTarget() ?: return null
                    PlayMethod.TRANSCODE -> source.transcodeTarget() ?: return null
                }

            return PlaybackMediaItemSpec(
                mediaId = source.itemId.toString(),
                uri = uri,
                mimeType = mimeType,
                subtitles = source.subtitleSpecs(),
            )
        }

        /**
         * A file on the server is streamed byte-for-byte; a source the server itself pulls over
         * HTTP (a live stream) is already a playlist and is handed over as-is.
         */
        private fun RemotePlaybackMediaSource.directPlayTarget(): Pair<String, String?>? =
            when (protocol) {
                MediaProtocol.FILE ->
                    urls.directPlayUrl(
                        itemId = itemId,
                        mediaSourceId = mediaSourceId,
                        playSessionId = playSessionId,
                    ) to null

                MediaProtocol.HTTP ->
                    when (val remote = path) {
                        null -> {
                            Timber.w("Direct play over HTTP without a path for %s", itemId)
                            null
                        }

                        else -> remote to MimeTypes.APPLICATION_M3U8
                    }

                else -> {
                    Timber.w("Unsupported protocol %s for %s", protocol, itemId)
                    null
                }
            }

        private fun RemotePlaybackMediaSource.directStreamTarget(): Pair<String, String?>? {
            val container = container
            if (container == null) {
                Timber.w("Direct stream without a container for %s", itemId)
                return null
            }
            return urls.directStreamUrl(
                itemId = itemId,
                container = container,
                mediaSourceId = mediaSourceId,
                playSessionId = playSessionId,
            ) to null
        }

        /**
         * Transcodes always arrive as HLS in this app — the device profile only ever asks for it,
         * so anything else means the server ignored the profile and the stream would not play.
         */
        private fun RemotePlaybackMediaSource.transcodeTarget(): Pair<String, String?>? {
            val path = transcodingUrl
            if (path == null) {
                Timber.w("Transcode without a transcoding URL for %s", itemId)
                return null
            }
            if (transcodingSubProtocol != MediaStreamProtocol.HLS) {
                Timber.w("Unsupported transcode protocol %s for %s", transcodingSubProtocol, itemId)
                return null
            }
            return urls.absoluteUrl(path) to MimeTypes.APPLICATION_M3U8
        }

        /**
         * External subtitles become their own ExoPlayer sources, tagged `external:<jellyfinIndex>`
         * so a selected text track can be mapped back onto the Jellyfin stream it came from.
         */
        private fun RemotePlaybackMediaSource.subtitleSpecs(): List<SubtitleSpec> =
            externalSubtitles.map { subtitle ->
                SubtitleSpec(
                    id = externalSubtitleTrackId(subtitle.index),
                    uri = urls.absoluteUrl(subtitle.url),
                    mimeType = subtitle.mimeType,
                    label = subtitle.label,
                    language = subtitle.language,
                )
            }
    }
