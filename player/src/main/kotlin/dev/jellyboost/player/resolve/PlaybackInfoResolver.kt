package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.UNDEFINED_LANGUAGE
import dev.jellyboost.core.network.runCatchingApi
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.bitrate.AutoBitrateDetector
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.deviceprofile.CastDeviceProfile
import dev.jellyboost.player.deviceprofile.CodecHelpers
import dev.jellyboost.player.deviceprofile.DeviceProfileBuilder
import dev.jellyboost.player.model.ExternalSubtitle
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * `POST /Items/{id}/PlaybackInfo` takes [DeviceProfileBuilder]'s profile and answers with a source
 * that is directly playable, remuxable, or accompanied by a transcoding URL. Everything downstream —
 * URL construction, reporting, track switching — is driven by that answer.
 */
@Singleton
internal class PlaybackInfoResolver
    @Inject
    constructor(
        private val api: PlayerApi,
        private val deviceProfileBuilder: DeviceProfileBuilder,
        private val autoBitrateDetector: AutoBitrateDetector,
        private val castStatus: CastStatusHolder,
    ) {
        /**
         * The *whole* negotiation sits inside [runCatchingApi], not just the call: building the
         * profile and reading the response can both throw, and a 403 has to reach the session layer
         * as an authentication failure rather than as a server fault.
         */
        suspend fun resolve(request: PlaybackResolveRequest): AppResult<RemotePlaybackMediaSource> =
            when (val outcome = runCatchingApi { negotiateUnderTranscodeCeiling(request.withMeasuredCap()) }) {
                is AppResult.Success -> outcome.value
                is AppResult.Failure -> outcome
            }

        /**
         * `maxStreamingBitrate` does double duty: a ceiling for a direct play, but the bitrate a
         * transcode is asked to *produce* — which no measurement of the link can vouch for. MEASURED
         * against a real server: a 4K HEVC source at a 64.7 Mbps Auto cap ran 0.76× realtime (a
         * permanent stall) where the same file at [PlaybackQuality.HIGH]'s 20 Mbps rung ran 2.50×.
         * So an Auto *transcode* above that rung is re-negotiated at it; direct play and direct
         * stream keep the full measured cap, and a hand-picked cap is never touched.
         *
         * **Cast included.** A cast Auto request arrives uncapped ([withMeasuredCap]), and `null` is
         * the profile's own 120 Mbps — MEASURED as `VideoBitrate=119616000` on a cast transcode URL.
         * Every cast transcode re-encodes its video (`allowVideoStreamCopy`, see
         * [toPlaybackInfoDto]), and asking an encoder for that bitrate is the 0.76× stall above
         * waiting to happen, so a cast Auto transcode is walked back to the same rung. A cast direct
         * play keeps the uncapped answer: its bytes are the file's, and the receiver's link is not
         * ours to cap.
         *
         * The abandoned negotiation costs one round trip and no encoder: ffmpeg is spawned by the
         * first *segment* fetch. The retry keeps `autoBitrate`, so the chip does not become "High".
         */
        private suspend fun negotiateUnderTranscodeCeiling(
            request: PlaybackResolveRequest,
        ): AppResult<RemotePlaybackMediaSource> {
            val negotiated = negotiate(request)
            val cap = request.maxStreamingBitrate
            // `null` is "over" for cast only: a local Auto request carries a measurement, and one
            // the detector could not make is not this rule's to second-guess.
            val capOverCeiling = if (cap == null) request.castTarget else cap > AUTO_TRANSCODE_CEILING
            val overCeiling =
                negotiated is AppResult.Success &&
                    negotiated.value.playMethod == PlayMethod.TRANSCODE &&
                    request.autoBitrate &&
                    capOverCeiling
            if (!overCeiling) return negotiated

            Timber.i(
                "Auto cap %s bps (cast=%b) but the server chose to transcode; re-negotiating at %d",
                cap?.toString() ?: "uncapped",
                request.castTarget,
                AUTO_TRANSCODE_CEILING,
            )
            return negotiate(request.copy(maxStreamingBitrate = AUTO_TRANSCODE_CEILING))
        }

        /**
         * Cast Auto stays unmeasured on purpose: the link that decides whether a receiver copes is
         * the receiver's, so measuring here would cap a television by this device's Wi-Fi. It is not
         * uncapped end to end, though: a cast *transcode* is walked back to [AUTO_TRANSCODE_CEILING]
         * by [negotiateUnderTranscodeCeiling], and only a direct play keeps the profile's ceiling.
         */
        private suspend fun PlaybackResolveRequest.withMeasuredCap(): PlaybackResolveRequest =
            when {
                !autoBitrate -> this
                castTarget -> copy(maxStreamingBitrate = null)
                else -> copy(maxStreamingBitrate = autoBitrateDetector.currentCap())
            }

        /**
         * Re-negotiates once when a transcode would side-load its subtitles: side-loaded cues are
         * their own `SubtitleConfiguration` and never pass through the `TimestampAdjuster` the
         * transcode's A/V do, so they drift progressively and unfixably. In-manifest renditions
         * share that adjuster (`X-TIMESTAMP-MAP`, `CopyTimestamps=true`) and cannot drift.
         *
         * Two passes rather than one profile: given both an `External` and an `Hls` profile for a
         * format the server always picks External, so the HLS shape must advertise no text External
         * profile at all — and that shape sent for a direct-played file with a sidecar `.srt` would
         * negotiate `Encode` and burn a transcode out of nothing. Cast is never re-asked.
         */
        private suspend fun negotiate(request: PlaybackResolveRequest): AppResult<RemotePlaybackMediaSource> {
            val first = negotiateWith(request, hlsTextSubtitles = false)
            if (request.castTarget || !first.sideLoadsTranscodedSubtitles()) return first

            Timber.i("Transcode side-loads text subtitles for %s; re-asking for HLS renditions", request.itemId)
            return inManifestSubtitles(request) ?: first
        }

        /**
         * `null` for every way the second pass can disappoint: pass 1's answer is still a playable
         * stream, so none of them is worth failing the open for.
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun inManifestSubtitles(
            request: PlaybackResolveRequest,
        ): AppResult<RemotePlaybackMediaSource>? =
            try {
                negotiateWith(request, hlsTextSubtitles = true).takeIf { negotiated ->
                    negotiated is AppResult.Success &&
                        negotiated.value.playMethod == PlayMethod.TRANSCODE &&
                        negotiated.value.transcodingUrl != null
                } ?: run {
                    Timber.w("HLS subtitle pass for %s did not transcode; keeping side-loaded cues", request.itemId)
                    null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "HLS subtitle pass for %s failed; keeping side-loaded cues", request.itemId)
                null
            }

        /**
         * Read off [RemotePlaybackMediaSource.externalSubtitles], not the raw delivery methods: that
         * list is exactly the set that would drift.
         */
        private fun AppResult<RemotePlaybackMediaSource>.sideLoadsTranscodedSubtitles(): Boolean =
            this is AppResult.Success &&
                value.playMethod == PlayMethod.TRANSCODE &&
                value.externalSubtitles.isNotEmpty()

        /** Every throw it makes is [resolve]'s to translate. */
        private suspend fun negotiateWith(
            request: PlaybackResolveRequest,
            hlsTextSubtitles: Boolean,
        ): AppResult<RemotePlaybackMediaSource> {
            val response =
                api.getPlaybackInfo(
                    itemId = request.itemId,
                    request = request.toPlaybackInfoDto(hlsTextSubtitles),
                )

            val playSessionId = response.playSessionId
            val source = response.mediaSources.pickFor(request)

            return when {
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
        }

        /**
         * Mints a play session id for an item played **off disk**, so a group's dashboard is not
         * silent about it. Deliberately sends **no device profile** (the server then has nothing to
         * build a transcode plan from) and **no live stream** (`autoOpenLiveStream = false`), so no
         * encoder or tuner is allocated for bytes that will not be used.
         *
         * @return `null` is a normal outcome, not an error: reporting degrades to sending no session
         *   id, which the server keys on the authenticated device anyway.
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun mintPlaySessionId(
            itemId: UUID,
            mediaSourceId: String?,
        ): String? =
            try {
                api
                    .getPlaybackInfo(
                        itemId = itemId,
                        request =
                            PlaybackInfoDto(
                                // The dash-less quirk applies here too; see toPlaybackInfoDto.
                                mediaSourceId =
                                    mediaSourceId?.replace("-", "")
                                        ?: itemId.toString().replace("-", ""),
                                autoOpenLiveStream = false,
                            ),
                    ).playSessionId
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Could not mint a play session id for %s; reporting without one", itemId)
                null
            }

        private fun PlaybackResolveRequest.toPlaybackInfoDto(hlsTextSubtitles: Boolean): PlaybackInfoDto =
            PlaybackInfoDto(
                // THE DASH-LESS QUIRK: the server looks media sources up by a *dash-less* id, and
                // when it cannot find the one asked for it silently ignores the stream indices
                // instead of failing — which surfaces much later as "subtitle selection does
                // nothing".
                mediaSourceId = mediaSourceId ?: itemId.toString().replace("-", ""),
                // The profile claims what the decoders at the far end can do, and while casting
                // those are the receiver's: sending the probed local profile is how a file that
                // plays in the hand becomes a black screen on the television.
                deviceProfile =
                    if (castTarget) {
                        // Read at negotiation time, not carried on the request: a re-negotiation
                        // mid-session must describe the receiver that is actually connected.
                        CastDeviceProfile.build(
                            maxStreamingBitrate = maxStreamingBitrate,
                            receiver = castStatus.receiver,
                        )
                    } else {
                        deviceProfileBuilder.getDeviceProfile(
                            maxStreamingBitrate = maxStreamingBitrate,
                            hlsTextSubtitles = hlsTextSubtitles,
                        )
                    },
                maxStreamingBitrate = maxStreamingBitrate,
                startTimeTicks = startPositionTicks.takeIf { it > 0L },
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                autoOpenLiveStream = true,
                enableDirectPlay = enableDirectPlay,
                enableDirectStream = enableDirectStream,
                // A cast transcode always re-encodes its video. MEASURED against 10.11.11: a
                // stream-copied HLS-ts playlist is laid out on the file's own keyframes, but an
                // ffmpeg restarted mid-file (resume, transfer, a seek past what is encoded) cuts at
                // `-hls_time` from its restart point, so segments go missing or change length under
                // the playlist, and a receiver that trusts the playlist buffers forever. Re-encoded,
                // the segments are fixed-length and match it. The server plans no copy and appends
                // `allowVideoStreamCopy=false` to the `TranscodingUrl` itself; direct play and
                // direct stream never consult the flag, and audio copy is left alone. Local playback
                // keeps the server's default: ExoPlayer was never measured stalling on it.
                allowVideoStreamCopy = if (castTarget) false else null,
            )

        /**
         * Ids come back *with* dashes even though the request used the dash-less form, so both
         * spellings are normalised before matching.
         */
        private fun List<MediaSourceInfo>.pickFor(request: PlaybackResolveRequest): MediaSourceInfo? {
            val wanted = (request.mediaSourceId ?: request.itemId.toString()).replace("-", "")
            return firstOrNull { it.id?.replace("-", "") == wanted } ?: firstOrNull()
        }

        /**
         * Ordered as jellyfin-web decides it: a source that can be direct-played is, even when a
         * transcoding URL is also offered — the server only reports `supportsDirectPlay` after
         * checking the file against the profile, which is what makes a low cap force a transcode.
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
                autoBitrate = request.autoBitrate,
                runTimeTicks = runTimeTicks ?: 0L,
                startPositionTicks = request.startPositionTicks,
                audioTracks = audio.map { it.toTrack(defaultAudioStreamIndex) },
                subtitleTracks = subtitles.map { it.toTrack(defaultSubtitleStreamIndex, it.sideLoadedSubtitle()) },
                externalSubtitles = subtitles.mapNotNull(MediaStream::toExternalSubtitle),
                selectedAudioIndex = request.audioStreamIndex ?: defaultAudioStreamIndex,
                selectedSubtitleIndex =
                    request.subtitleStreamIndex?.takeIf { it >= 0 }
                        ?: defaultSubtitleStreamIndex.takeIf { request.subtitleStreamIndex == null },
            )
        }

        private companion object {
            /**
             * The picker's own [PlaybackQuality.HIGH] rung rather than a second copy of 20 Mbps, so
             * the two paths cannot drift apart.
             */
            val AUTO_TRANSCODE_CEILING: Int = requireNotNull(PlaybackQuality.HIGH.maxStreamingBitrate)
        }
    }

/**
 * `internal` because [LocalPlaybackResolver] builds its lists from the same `MediaStream` shape and
 * the two must agree label for label — that identity is what keeps the UI the same offline.
 *
 * @param sideLoaded whether the track reaches ExoPlayer as its own source rather than out of the
 *   container: `TrackSelectionController` matches side-loaded groups by their `external:<index>` id
 *   and everything else by position among the *embedded* groups. The [MediaStream.isExternal]
 *   default is the offline caller's rule of thumb; online it is the delivery method that decides
 *   (`sideLoadedSubtitle`).
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
 * The server's chosen delivery answers this, not whether the stream happens to be a file:
 *
 * - `EMBED` and `HLS` arrive *inside* what the player opens and are matched by position — an
 *   HLS-delivered **sidecar** included, `isExternal` notwithstanding: a rendition carries no
 *   `external:<index>` id to match on.
 * - `EXTERNAL` is the side-loaded case proper, matched by that id.
 * - `ENCODE` is burned in and gets no rendition (Jellyfin only builds them for
 *   `IsTextSubtitleStream`), so counting it would push every later text track onto the wrong one.
 */
private fun MediaStream.sideLoadedSubtitle(): Boolean =
    when (deliveryMethod) {
        SubtitleDeliveryMethod.EMBED, SubtitleDeliveryMethod.HLS -> false
        SubtitleDeliveryMethod.EXTERNAL, SubtitleDeliveryMethod.ENCODE -> true
        else -> isExternal
    }

/**
 * `EXTERNAL` covers both genuinely external files and subtitles the server extracts while
 * transcoding; both a delivery URL and a MIME type ExoPlayer understands are required, so a PGS or
 * DVB stream never becomes one of these.
 */
@Suppress("ReturnCount")
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

/** `autoBitrate = true`: nobody has picked a quality yet, and [PlaybackInfoResolver] fills the cap. */
internal fun playbackResolveRequest(
    itemId: String,
    mediaSourceId: String? = null,
    startPositionTicks: Long = 0L,
): PlaybackResolveRequest =
    PlaybackResolveRequest(
        itemId = UUID.fromString(itemId),
        mediaSourceId = mediaSourceId,
        startPositionTicks = startPositionTicks,
        autoBitrate = true,
    )
