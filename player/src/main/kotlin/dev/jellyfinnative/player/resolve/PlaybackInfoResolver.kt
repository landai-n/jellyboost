package dev.jellyfinnative.player.resolve

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.api.PlayerApi
import dev.jellyfinnative.player.deviceprofile.CodecHelpers
import dev.jellyfinnative.player.deviceprofile.DeviceProfileBuilder
import dev.jellyfinnative.player.model.ExternalSubtitle
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Negotiates with the server how a given item should be played.
 *
 * `POST /Items/{id}/PlaybackInfo` takes the device profile built by [DeviceProfileBuilder] and
 * answers with a media source that is either directly playable, remuxable, or accompanied by a
 * transcoding URL. Everything downstream — URL construction, reporting, track switching — is
 * driven by that answer, which is why this is the one place the decision is made.
 */
@Singleton
class PlaybackInfoResolver
    @Inject
    constructor(
        private val api: PlayerApi,
        private val deviceProfileBuilder: DeviceProfileBuilder,
    ) {
        /**
         * Resolves [request] into something the player can open.
         *
         * @return a [RemotePlaybackMediaSource], or an [AppError] describing why not. Transport
         *   failures are folded the same way `:core:network` folds them, so the player's error
         *   copy can be shared with the rest of the app.
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun resolve(request: PlaybackResolveRequest): AppResult<RemotePlaybackMediaSource> =
            try {
                val response =
                    api.getPlaybackInfo(
                        itemId = request.itemId,
                        request = request.toPlaybackInfoDto(),
                    )

                val playSessionId = response.playSessionId
                val source = response.mediaSources.pickFor(request)

                when {
                    playSessionId == null || source == null -> {
                        Timber.w("Server returned no playable media source for %s", request.itemId)
                        AppResult.Failure(AppError.NotFound(request.itemId.toString()))
                    }

                    else ->
                        when (val method = source.playMethod()) {
                            null -> {
                                Timber.w("No supported play method for %s", request.itemId)
                                AppResult.Failure(AppError.Server(statusCode = null))
                            }

                            else ->
                                AppResult.Success(
                                    source.toMediaSource(request, playSessionId, method),
                                )
                        }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: InvalidStatusException) {
                AppResult.Failure(
                    if (error.status == HTTP_UNAUTHORIZED) {
                        AppError.Unauthorized(error)
                    } else {
                        AppError.Server(statusCode = error.status, cause = error)
                    },
                )
            } catch (error: TimeoutException) {
                AppResult.Failure(AppError.Network(error))
            } catch (error: IOException) {
                AppResult.Failure(AppError.Network(error))
            } catch (error: ApiClientException) {
                AppResult.Failure(AppError.Network(error))
            } catch (error: Exception) {
                AppResult.Failure(AppError.Unknown(error))
            }

        private fun PlaybackResolveRequest.toPlaybackInfoDto(): PlaybackInfoDto =
            PlaybackInfoDto(
                // THE DASH-LESS QUIRK. The server looks media sources up by a *dash-less* id, and
                // when it cannot find the one we asked for it silently ignores our stream indices
                // instead of failing — so a wrong id here shows up much later as "subtitle
                // selection does nothing". Verified against jellyfin-android's
                // MediaSourceResolver.kt:58 and Jellyfin's MediaInfoHelper.cs:196-201.
                mediaSourceId = mediaSourceId ?: itemId.toString().replace("-", ""),
                deviceProfile = deviceProfileBuilder.getDeviceProfile(maxStreamingBitrate = maxStreamingBitrate),
                maxStreamingBitrate = maxStreamingBitrate,
                startTimeTicks = startPositionTicks.takeIf { it > 0L },
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                autoOpenLiveStream = true,
                enableDirectPlay = enableDirectPlay,
                enableDirectStream = enableDirectStream,
            )

        /**
         * Picks the media source the request asked for.
         *
         * The server answers with every source of the item, and their ids come back *with* dashes
         * even though the request used the dash-less form — so both spellings have to match.
         */
        private fun List<MediaSourceInfo>.pickFor(request: PlaybackResolveRequest): MediaSourceInfo? {
            val wanted = (request.mediaSourceId ?: request.itemId.toString()).replace("-", "")
            return firstOrNull { it.id?.replace("-", "") == wanted } ?: firstOrNull()
        }

        /**
         * The delivery method the server settled on.
         *
         * Ordered exactly as jellyfin-android and jellyfin-web decide it: a source that can be
         * direct-played is direct-played even when a transcoding URL is also offered, because the
         * server only reports `supportsDirectPlay` after checking the file against our profile.
         * A transcoding URL is therefore only reached once both cheaper options are ruled out —
         * which is what makes a low `maxStreamingBitrate` a reliable way to force a transcode.
         */
        private fun MediaSourceInfo.playMethod(): PlayMethod? =
            when {
                supportsDirectPlay -> PlayMethod.DIRECT_PLAY
                supportsDirectStream -> PlayMethod.DIRECT_STREAM
                transcodingUrl != null || supportsTranscoding -> PlayMethod.TRANSCODE
                else -> null
            }

        private fun MediaSourceInfo.toMediaSource(
            request: PlaybackResolveRequest,
            playSessionId: String,
            method: PlayMethod,
        ): RemotePlaybackMediaSource {
            val streams = mediaStreams.orEmpty()
            val audio = streams.filter { it.type == MediaStreamType.AUDIO }
            val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }

            return RemotePlaybackMediaSource(
                itemId = request.itemId,
                mediaSourceId = requireNotNull(id) { "Media source without an id" },
                playSessionId = playSessionId,
                playMethod = method,
                container = container,
                protocol = protocol,
                path = path,
                transcodingUrl = transcodingUrl,
                transcodingSubProtocol = transcodingSubProtocol,
                liveStreamId = liveStreamId,
                maxStreamingBitrate = request.maxStreamingBitrate,
                runTimeTicks = runTimeTicks ?: 0L,
                startPositionTicks = request.startPositionTicks,
                audioTracks = audio.map { it.toTrack(defaultAudioStreamIndex) },
                subtitleTracks = subtitles.map { it.toTrack(defaultSubtitleStreamIndex) },
                externalSubtitles = subtitles.mapNotNull(MediaStream::toExternalSubtitle),
                selectedAudioIndex = request.audioStreamIndex ?: defaultAudioStreamIndex,
                selectedSubtitleIndex =
                    request.subtitleStreamIndex?.takeIf { it >= 0 }
                        ?: defaultSubtitleStreamIndex.takeIf { request.subtitleStreamIndex == null },
            )
        }

        private companion object {
            const val HTTP_UNAUTHORIZED = 401
        }
    }

/**
 * One stream as the pickers see it.
 *
 * `internal` rather than file-private since M8: [LocalPlaybackResolver] builds its track lists from
 * the very same `MediaStream` shape (read out of the cached item blob instead of off a
 * `PlaybackInfo` response), and the two must agree label for label — that identity is what makes
 * the player UI the same online and offline.
 *
 * @param sideLoaded whether the track reaches ExoPlayer as its own source rather than out of the
 *   container — which is what [PlaybackTrack.isExternal] actually drives, since
 *   `TrackSelectionController` matches side-loaded groups by their `external:<index>` id and counts
 *   everything else by position among the *embedded* groups. It defaults to
 *   [MediaStream.isExternal] because online the two coincide. Offline they need not: a transcoded
 *   download side-loads a sidecar for an **embedded** subtitle the server extracted for it, and
 *   calling that track embedded would have it looked for among container groups the encode dropped.
 */
internal fun MediaStream.toTrack(
    defaultIndex: Int?,
    sideLoaded: Boolean = isExternal,
): PlaybackTrack =
    PlaybackTrack(
        index = index,
        label = displayTitle ?: title ?: language ?: codec.orEmpty(),
        language = language,
        codec = codec,
        isDefault = index == defaultIndex,
        isExternal = sideLoaded,
    )

/**
 * A subtitle stream ExoPlayer has to fetch separately, or `null` when it travels in the container.
 *
 * `SubtitleDeliveryMethod.EXTERNAL` covers both genuinely external files and subtitles the server
 * extracts on the fly while transcoding; either way the delivery URL and a MIME type ExoPlayer
 * understands are both required, so a PGS or DVB stream never becomes one of these.
 */
private fun MediaStream.toExternalSubtitle(): ExternalSubtitle? {
    if (deliveryMethod != SubtitleDeliveryMethod.EXTERNAL) return null
    val url = deliveryUrl ?: return null
    val mimeType = CodecHelpers.subtitleMimeType(codec) ?: return null
    return ExternalSubtitle(
        index = index,
        url = url,
        mimeType = mimeType,
        label = displayTitle.orEmpty(),
        language = language ?: UNDEFINED_LANGUAGE,
    )
}

/** ISO 639-2 code the Jellyfin server uses when a stream declares no language. */
private const val UNDEFINED_LANGUAGE = "und"

/** Small helper so callers can build a request from the route's string item id. */
fun playbackResolveRequest(
    itemId: String,
    mediaSourceId: String? = null,
    startPositionTicks: Long = 0L,
): PlaybackResolveRequest =
    PlaybackResolveRequest(
        itemId = UUID.fromString(itemId),
        mediaSourceId = mediaSourceId,
        startPositionTicks = startPositionTicks,
    )
