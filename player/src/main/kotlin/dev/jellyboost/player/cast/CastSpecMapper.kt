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
 * Turns what ExoPlayer would have been handed into what a Cast receiver has to be handed.
 *
 * Two differences carry the whole class, and both fail invisibly if they are got wrong.
 *
 * **Credentials.** Every URL the app itself opens is authorised by `JellyfinAuthInterceptor`, an
 * OkHttp interceptor on the media client. A receiver fetches its own bytes over its own network
 * stack, so nothing of ours is in that loop: the token has to travel in the URL, on the media URL,
 * on every subtitle URL *and* on the poster the television draws. Probed against the dev server
 * (2026-07-31), a transcode's
 * `TranscodingUrl` and every external-subtitle `DeliveryUrl` already arrive with `ApiKey` on them —
 * but a direct-play or direct-stream URL, which the SDK builds locally, does not. Applying
 * [StreamUrlFactory.withApiKey] to all of them and relying on its idempotence is what covers both
 * without a table of which endpoint does what.
 *
 * **Track ids.** ExoPlayer identifies a side-loaded subtitle by the `external:<index>` string id
 * `ExoMediaSourceFactory` invents for it; Cast identifies one by a numeric id the sender chooses.
 * Choosing the Jellyfin stream index makes the two vocabularies the same one.
 *
 * Pure — no `com.google.android.gms` type appears here, which is the point: this is where the
 * decisions are, so this is what the tests cover.
 */
@Singleton
class CastSpecMapper
    @Inject
    constructor(
        private val urls: StreamUrlFactory,
    ) {
        /**
         * @param spec what the same source would have been opened with locally; its URL and
         *   side-loaded subtitles are already the right ones, and only their credentials and their
         *   ids need translating.
         * @param metadata what the receiver should display; the player screen owns it
         *   ([CastMetadataHolder]), so it is passed in rather than dug out of the negotiation.
         */
        fun map(
            spec: PlaybackMediaItemSpec,
            source: RemotePlaybackMediaSource,
            metadata: CastMetadata = CastMetadata(),
        ): CastMediaSpec =
            CastMediaSpec(
                mediaId = spec.mediaId,
                contentId = urls.withApiKey(spec.uri),
                contentType = contentTypeOf(spec, source),
                // A runtime the server does not know is a live source; it is the only case where
                // there is nothing to seek within and no end to play to.
                streamType = if (source.runTimeTicks > 0L) CastStreamType.Buffered else CastStreamType.Live,
                durationMs = source.runTimeTicks.ticksToMillis(),
                startPositionMs = source.startPositionTicks.ticksToMillis(),
                metadata = metadata.signed(),
                tracks = spec.tracks(),
            )

        /**
         * The metadata with its poster addressed to a fetcher that is not this app.
         *
         * The title and the subtitle are strings and pass through untouched; the poster is a third
         * URL the receiver opens itself, and it belongs under the same rule as the media and the
         * subtitles rather than under an exception nobody would remember. Probed against the dev
         * server (2026-07-31): image endpoints answer `200` with no credentials at all, so today the
         * token changes nothing — but that is the *server's* current policy, not a property of the
         * URL, and the cost of being wrong about it is a television showing a blank card.
         *
         * Idempotent through [StreamUrlFactory.withApiKey], like every other URL here.
         */
        private fun CastMetadata.signed(): CastMetadata =
            posterUrl?.let { copy(posterUrl = urls.withApiKey(it)) } ?: this

        /**
         * The MIME type the receiver is told to expect.
         *
         * A transcode — and a live source the server hands over as a playlist — is HLS, which the
         * local spec already marks. Anything else is a file, and under `CastDeviceProfile` the only
         * containers the server may direct-play or direct-stream are the two below; a container that
         * somehow arrives anyway is announced as `mp4`, because guessing the container that was
         * negotiated *for* is a better error than admitting one the receiver has no decoder for.
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
         * The side-loaded subtitles, renumbered onto their Jellyfin stream indices.
         *
         * A subtitle whose id is not one of ours is dropped rather than given an invented id: the
         * only thing a Cast track id is ever compared against is the index the picker asks for, so a
         * track that cannot be addressed is a track that could only be turned on and never off.
         *
         * The MIME type is forced to WebVTT rather than copied across. That is not a correction of
         * the local spec — there the codec named is the *source's*, which is right — but the cast
         * profile declares WebVTT as the only external subtitle format, so whatever the source
         * stream was, what its delivery URL serves is `.vtt`.
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
        }
    }
