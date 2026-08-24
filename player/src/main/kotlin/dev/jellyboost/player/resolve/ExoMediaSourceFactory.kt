package dev.jellyboost.player.resolve

import androidx.media3.common.MimeTypes
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.api.StreamUrlFactory
import dev.jellyboost.player.model.AudioSidecarSpec
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.model.SubtitleSpec
import dev.jellyboost.player.model.externalSubtitleTrackId
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Getting this table wrong fails in ways that look like a decoder problem: a direct-play URL without
 * `static=true` quietly remuxes, and an HLS playlist opened without its MIME type parses as a progressive
 * stream.
 */
@Singleton
internal class ExoMediaSourceFactory
    @Inject
    constructor(
        private val urls: StreamUrlFactory,
    ) {
        /** @return `null` when the source cannot be played at all: an unknown protocol, or a non-HLS transcode. */
        fun create(source: PlaybackMediaSource): PlaybackMediaItemSpec? =
            when (source) {
                is LocalPlaybackMediaSource -> source.toSpec()
                is RemotePlaybackMediaSource -> source.toSpec()
            }

        @Suppress(
            // One return per protocol: a dispatch table written as guards.
            "ReturnCount",
        )
        private fun RemotePlaybackMediaSource.toSpec(): PlaybackMediaItemSpec? {
            val (uri, mimeType) =
                when (playMethod) {
                    PlayMethod.DIRECT_PLAY -> directPlayTarget() ?: return null
                    PlayMethod.DIRECT_STREAM -> directStreamTarget() ?: return null
                    PlayMethod.TRANSCODE -> transcodeTarget() ?: return null
                }

            return PlaybackMediaItemSpec(
                mediaId = itemId.toString(),
                uri = uri,
                mimeType = mimeType,
                subtitles = subtitleSpecs(),
            )
        }

        /**
         * The MIME type is deliberately unset: ExoPlayer sniffs a local file more reliably than an extension the
         * download pipeline copied from the server. Sidecar subtitles are already `file://` URIs and must
         * **not** go through [StreamUrlFactory.absoluteUrl].
         *
         * Audio sidecar order is load-bearing: the positions *are* the merge-child indices `ExoPlayerHandle`
         * builds and `TrackSelectionController` reads back, so re-sorting plays the wrong language.
         */
        private fun LocalPlaybackMediaSource.toSpec(): PlaybackMediaItemSpec =
            PlaybackMediaItemSpec(
                mediaId = itemId.toString(),
                uri = mediaUri,
                mimeType = null,
                subtitles =
                    externalSubtitles.map { subtitle ->
                        SubtitleSpec(
                            id = externalSubtitleTrackId(subtitle.index),
                            uri = subtitle.url,
                            mimeType = subtitle.mimeType,
                            label = subtitle.label,
                            language = subtitle.language,
                        )
                    },
                audioSidecars =
                    externalAudio.map { audio ->
                        AudioSidecarSpec(streamIndex = audio.index, uri = audio.uri)
                    },
            )

        /** A source the server pulls over HTTP (a live stream) is already a playlist and is handed over as-is. */
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

        /** The device profile only ever asks for HLS, so anything else means the server ignored it. */
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

        /** Tagged `external:<jellyfinIndex>` so a selected text track maps back onto its Jellyfin stream. */
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
