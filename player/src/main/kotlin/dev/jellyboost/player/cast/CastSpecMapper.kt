package dev.jellyboost.player.cast

import androidx.media3.common.MimeTypes
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.api.StreamUrlFactory
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.model.jellyfinIndexOfTrackId
import dev.jellyboost.player.model.ticksToMillis
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns what ExoPlayer would have been handed into what a Cast receiver has to be handed. Must stay
 * pure — no `com.google.android.gms` type here, so the decisions are testable.
 *
 * **Credentials.** A receiver fetches its own bytes, outside `JellyfinAuthInterceptor`, so the token
 * has to travel in the media and subtitle URLs — [StreamUrlFactory.withApiKey] is idempotent, so it
 * is applied to all of them. The poster is the deliberate exception (see [map]).
 *
 * **Track ids.** Cast identifies a subtitle by a numeric id the sender chooses; using the Jellyfin
 * stream index makes it the same vocabulary as ExoPlayer's `external:<index>`.
 */
@Singleton
internal class CastSpecMapper
    @Inject
    constructor(
        private val urls: StreamUrlFactory,
    ) {
        /** @param metadata owned by the player screen ([CastMetadataHolder]), not the negotiation. */
        fun map(
            spec: PlaybackMediaItemSpec,
            source: RemotePlaybackMediaSource,
            metadata: CastMetadata = CastMetadata(),
        ): CastMediaSpec {
            val contentType = contentTypeOf(spec, source)
            return CastMediaSpec(
                mediaId = spec.mediaId,
                contentId = urls.withApiKey(spec.uri),
                contentType = contentType,
                // A runtime the server does not know means a live source: nothing to seek within.
                streamType = if (source.runTimeTicks > 0L) CastStreamType.Buffered else CastStreamType.Live,
                durationMs = source.runTimeTicks.ticksToMillis(),
                startPositionMs = source.startPositionTicks.ticksToMillis(),
                // The poster stays *unsigned*: image endpoints answer without credentials, and every
                // URL handed to the receiver is republished in its `MediaStatus` for any sender to
                // read. Signing it would leak the token for nothing.
                metadata = metadata,
                tracks = spec.tracks(),
                hlsSegmentMs = hlsSegmentMsOf(source, contentType),
            )
        }

        /**
         * The server's segment length, for the one shape whose segments it lays out on a fixed grid: an
         * HLS transcode that **re-encodes** the video (`allowVideoStreamCopy=false`, which every cast
         * transcode asks for and the server echoes into the URL). A stream copy is laid out on the
         * file's keyframes, a URL asking for its own frame rate may be encoded at another one — in both
         * cases, and whenever the frame rate is unknown, there is no grid to snap to: `null`. A
         * `MaxFramerate` cap at or above the source's rate (which every cast transcode URL carries) moves
         * nothing and keeps the grid.
         * A `SegmentLength` the URL carries replaces the nominal 3 s.
         */
        private fun hlsSegmentMsOf(
            source: RemotePlaybackMediaSource,
            contentType: String,
        ): Double? {
            val onGrid =
                source.playMethod == PlayMethod.TRANSCODE &&
                    contentType == MimeTypes.APPLICATION_M3U8 &&
                    source.runTimeTicks > 0L
            val query = source.transcodingUrl?.takeIf { onGrid }?.let(::queryOf) ?: return null
            val reEncoded = query.param("allowVideoStreamCopy").equals("false", ignoreCase = true)
            // A cap at or above the source's own rate leaves it alone (the cast profile always sends
            // one): only a cap below it, an unreadable one or an explicit rate moves the grid.
            val sourceFps = source.videoFrameRate
            val ownFrameRate =
                query.param("Framerate") != null ||
                    query.param("MaxFramerate")?.let { cap ->
                        val capFps = cap.toFloatOrNull()
                        capFps == null || sourceFps == null || capFps < sourceFps - FRAME_RATE_TOLERANCE
                    } == true
            // An unreadable length is no length (0), never the nominal one.
            val nominal =
                query.param("SegmentLength")?.let { it.toIntOrNull() ?: 0 }
                    ?: HlsSegmentSnap.NOMINAL_SEGMENT_SECONDS
            return if (reEncoded && !ownFrameRate) HlsSegmentSnap.segmentMs(source.videoFrameRate, nominal) else null
        }

        private fun queryOf(url: String): List<Pair<String, String>> =
            url
                .substringAfter('?', missingDelimiterValue = "")
                .split('&')
                .filter { it.isNotEmpty() }
                .map { it.substringBefore('=') to it.substringAfter('=', "") }

        /** The server's parameter names are case-insensitive. */
        private fun List<Pair<String, String>>.param(name: String): String? =
            firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        /**
         * `CastDeviceProfile` allows only the two containers below for direct play/stream; anything
         * else is announced as `mp4`, a better guess than a container the receiver cannot decode.
         */
        private fun contentTypeOf(
            spec: PlaybackMediaItemSpec,
            source: RemotePlaybackMediaSource,
        ): String =
            when {
                spec.mimeType == MimeTypes.APPLICATION_M3U8 -> MimeTypes.APPLICATION_M3U8
                source.playMethod == PlayMethod.TRANSCODE -> MimeTypes.APPLICATION_M3U8
                source.container.equals("webm", ignoreCase = true) -> WEBM_CONTENT_TYPE
                else -> MP4_CONTENT_TYPE
            }

        /**
         * Renumbered onto Jellyfin stream indices; an unrecognised id is dropped rather than invented,
         * since a Cast track id is only ever compared against the index the picker asks for.
         *
         * The MIME type is forced to WebVTT, not copied: the cast profile declares WebVTT as the only
         * external subtitle format, so the delivery URL serves `.vtt` whatever the source stream was.
         */
        private fun PlaybackMediaItemSpec.tracks(): List<CastTrackSpec> =
            subtitles.mapNotNull { subtitle ->
                val index = jellyfinIndexOfTrackId(subtitle.id)
                if (index == null) {
                    Timber.w("Dropping cast subtitle with unrecognised id %s", subtitle.id)
                    return@mapNotNull null
                }
                CastTrackSpec(
                    id = index,
                    uri = urls.withApiKey(subtitle.uri),
                    mimeType = MimeTypes.TEXT_VTT,
                    label = subtitle.label,
                    language = subtitle.language,
                )
            }

        private companion object {
            const val MP4_CONTENT_TYPE = "video/mp4"
            const val WEBM_CONTENT_TYPE = "video/webm"

            /**
             * Float rounding only: a 23.976 cap on a 24 fps source is a real cap (it moves the grid to
             * 3.003 s), so the slack stays well under that 0.024 fps gap.
             */
            const val FRAME_RATE_TOLERANCE = 0.001f
        }
    }
