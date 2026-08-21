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
 * Negotiates with the server how a given item should be played.
 *
 * `POST /Items/{id}/PlaybackInfo` takes the device profile built by [DeviceProfileBuilder] and
 * answers with a media source that is either directly playable, remuxable, or accompanied by a
 * transcoding URL. Everything downstream — URL construction, reporting, track switching — is
 * driven by that answer, which is why this is the one place the decision is made.
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
         * Resolves [request] into something the player can open.
         *
         * Transport failures fold through `:core:network`'s [runCatchingApi] — the app's one
         * exception→[AppError] mapper. This used to hold a nineteen-line copy of it, made when that
         * mapper was `internal` and unreachable from here, and the copy had drifted: a 403 was
         * reported as a server fault rather than an authentication failure, so a revoked token
         * discovered at `/PlaybackInfo` never reached the session layer (audit DUP-1).
         *
         * The whole negotiation, not just the call, sits inside it: building the device profile and
         * reading the response are both places the SDK can throw, and both belong in the same
         * taxonomy as the call itself — as is the throughput measurement an Auto request triggers,
         * which is answered from `AutoBitrateDetector` before the negotiation begins.
         *
         * @return a [RemotePlaybackMediaSource], or an [AppError] describing why not.
         */
        suspend fun resolve(request: PlaybackResolveRequest): AppResult<RemotePlaybackMediaSource> =
            when (val outcome = runCatchingApi { negotiateUnderTranscodeCeiling(request.withMeasuredCap()) }) {
                is AppResult.Success -> outcome.value
                is AppResult.Failure -> outcome
            }

        /**
         * Negotiates [request], and re-negotiates once if Auto's measured cap became a transcode's
         * *target* (DECISIONS.md, 2026-08-15 amendment).
         *
         * `maxStreamingBitrate` does double duty: to a direct play it is a ceiling the file either
         * fits under or does not, and a high one is exactly the point — the original bytes, no
         * re-encode. To a transcode it is the bitrate the server is asked to *produce*, and no
         * measurement of the **link** can say whether the encoder-plus-link chain can produce it in
         * realtime. Measured on the user's server: at a 64.7 Mbps Auto cap a 4K HEVC source was
         * delivered at 0.76× realtime — a permanent stall — where the same file at
         * [PlaybackQuality.HIGH]'s 20 Mbps rung ran 2.50× realtime, and 20 Mbps is already
         * transparent for a 1080p transcode. So an Auto transcode above that rung is re-negotiated
         * at it; direct play and direct stream keep the full measured cap, and a hand-picked cap is
         * never touched.
         *
         * The abandoned first negotiation costs nothing but its round trip: ffmpeg is spawned by the
         * first *segment* fetch, not by `PlaybackInfo`, and nothing here fetches one.
         *
         * The re-negotiated request keeps [PlaybackResolveRequest.autoBitrate], so the resolved
         * source still reads as Auto and the picker's chip does not silently become "High".
         */
        private suspend fun negotiateUnderTranscodeCeiling(
            request: PlaybackResolveRequest,
        ): AppResult<RemotePlaybackMediaSource> {
            val negotiated = negotiate(request)
            val cap = request.maxStreamingBitrate
            val overCeiling =
                negotiated is AppResult.Success &&
                    negotiated.value.playMethod == PlayMethod.TRANSCODE &&
                    request.autoBitrate &&
                    !request.castTarget &&
                    cap != null &&
                    cap > AUTO_TRANSCODE_CEILING
            if (!overCeiling) return negotiated

            Timber.i(
                "Auto measured %d bps but the server chose to transcode; re-negotiating at %d",
                cap,
                AUTO_TRANSCODE_CEILING,
            )
            return negotiate(request.copy(maxStreamingBitrate = AUTO_TRANSCODE_CEILING))
        }

        /**
         * The request as it should actually go out, with Auto's cap filled in.
         *
         * A `copy` of the caller's request rather than a separate cap value, so that
         * [PlaybackResolveRequest.autoBitrate] rides along onto the resolved source: everything
         * downstream reads the *effective* cap and the *original* flag off the same object.
         *
         * Cast Auto keeps today's uncapped behaviour on purpose. The link that decides whether a
         * receiver copes is the receiver's, not this device's, and the cast profile is already
         * conservative — measuring here would cap a television by a tablet's Wi-Fi
         * (DECISIONS.md, 2026-08-15).
         */
        private suspend fun PlaybackResolveRequest.withMeasuredCap(): PlaybackResolveRequest =
            when {
                !autoBitrate -> this
                castTarget -> copy(maxStreamingBitrate = null)
                else -> copy(maxStreamingBitrate = autoBitrateDetector.currentCap())
            }

        /**
         * Negotiates [request], and re-negotiates once when a transcode would side-load its
         * subtitles (DECISIONS.md, 2026-08-21).
         *
         * Side-loaded cues are their own `MediaItem.SubtitleConfiguration`, so they never pass
         * through the `TimestampAdjuster` that the transcode's audio and video do — and that
         * timeline is not the file's. It re-anchors to Jellyfin's nominal `EXTINF` grid on every
         * seek and track toggle (≈1 ms per segment on a fractional frame rate) and silently absorbs
         * the sub-200 ms audio gaps an unsignaled ffmpeg restart leaves. Cues fixed to the file's
         * clock drift away from a picture that is not on it, progressively and unfixably. Delivered
         * *in* the manifest they share the adjuster with A/V — the server emits `X-TIMESTAMP-MAP`
         * and `CopyTimestamps=true` — and the drift stops being possible rather than being corrected.
         *
         * Two passes rather than one profile because the server will not offer both: given an
         * `External` and an `Hls` profile for the same format it always picks External, so the HLS
         * shape has to advertise no text External profile at all — and that shape sent for a
         * direct-played file with a sidecar `.srt` would negotiate `Encode` and burn a transcode out
         * of nothing (see `DeviceProfileBuilder.subtitleProfiles`). So pass 1 asks the honest
         * question, and only an answer that is *already* a transcode with side-loaded text is asked
         * again.
         *
         * Like the ceiling re-negotiation above, the abandoned pass costs one round trip and no
         * encoder: ffmpeg is spawned by the first *segment* fetch, and nothing here fetches one.
         *
         * Cast is never re-asked — `CastDeviceProfile` describes a receiver whose subtitle handling
         * is its own, and `CastSpecMapper` builds its tracks from the side-loaded list.
         */
        private suspend fun negotiate(request: PlaybackResolveRequest): AppResult<RemotePlaybackMediaSource> {
            val first = negotiateWith(request, hlsTextSubtitles = false)
            if (request.castTarget || !first.sideLoadsTranscodedSubtitles()) return first

            Timber.i("Transcode side-loads text subtitles for %s; re-asking for HLS renditions", request.itemId)
            return inManifestSubtitles(request) ?: first
        }

        /**
         * The second pass, or `null` when it did not produce something better than pass 1.
         *
         * Every way it can disappoint is the same non-event: a transport failure, a server that
         * answered with a direct play after all, a transcode with no URL to fetch. Pass 1's answer is
         * still a perfectly playable stream — with cues that drift — so none of them is worth
         * failing the open for, and none of them is worth an error the user sees.
         */
        @Suppress("TooGenericExceptionCaught") // Any failure of an optimisation pass keeps pass 1's answer.
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
         * Whether this outcome is the one worth re-asking: a transcode whose text subtitles reach
         * ExoPlayer as separate sources.
         *
         * Read off [RemotePlaybackMediaSource.externalSubtitles] rather than off the raw delivery
         * methods, because that list is exactly the set that would drift — a text stream the server
         * offers as External but that has no delivery URL, or a codec ExoPlayer cannot render, is
         * never side-loaded and has nothing to gain from a second round trip.
         */
        private fun AppResult<RemotePlaybackMediaSource>.sideLoadsTranscodedSubtitles(): Boolean =
            this is AppResult.Success &&
                value.playMethod == PlayMethod.TRANSCODE &&
                value.externalSubtitles.isNotEmpty()

        /** One negotiation round trip; every throw it makes is [resolve]'s to translate. */
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
         * Opens a play session for an item that will be played **off disk**, and nothing else.
         *
         * A `LocalPlaybackMediaSource` has no play session by construction, which is exactly why
         * local playback tells the server nothing. In a SyncPlay group that silence is wrong: the
         * other members are watching together with this device, and the dashboard should say so
         * (docs/notes/syncplay-m11-plan.md, key decision 9). One `PlaybackInfo` POST is enough to
         * get the id every report is keyed on.
         *
         * Two things it deliberately does *not* do, because both would put load on a server whose
         * bytes we are not going to use:
         *
         * - **no device profile.** With none the server has nothing to build a transcode plan from,
         *   so the response is a bare description of the item. The id is the only field read.
         * - **no live stream.** `autoOpenLiveStream` stays `false`; opening one would allocate a
         *   tuner or a stream the file on disk makes pointless.
         *
         * An encoder can only start when the transcoding URL is *fetched*, and nothing here fetches
         * anything — so the worst case is a session row the stop report closes.
         *
         * @return the server's play session id, or `null` if the mint failed. `null` is a normal
         *   outcome and not an error: reporting degrades to sending no session id (the server keys
         *   the session on the authenticated device anyway), which is still better than silence.
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
                // THE DASH-LESS QUIRK. The server looks media sources up by a *dash-less* id, and
                // when it cannot find the one we asked for it silently ignores our stream indices
                // instead of failing — so a wrong id here shows up much later as "subtitle
                // selection does nothing". Verified against jellyfin-android's
                // MediaSourceResolver.kt:58 and Jellyfin's MediaInfoHelper.cs:196-201.
                mediaSourceId = mediaSourceId ?: itemId.toString().replace("-", ""),
                // The profile is a claim about the decoders on the far end of the stream, and while
                // casting those are the television's, not this tablet's. Sending the probed local
                // profile for a receiver is how a file that plays in the hand becomes a black screen
                // (docs/notes/chromecast-m12-plan.md, key decision 2).
                deviceProfile =
                    if (castTarget) {
                        // The receiver's class is read at negotiation time rather than carried on
                        // the request: the request describes what to play, the holder knows who is
                        // playing it, and a renegotiation mid-session should always describe the
                        // receiver that is actually connected (M12 phase-2a).
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
             * The highest bitrate an Auto negotiation will ask a *transcode* to hit.
             *
             * Deliberately the picker's own [PlaybackQuality.HIGH] rung rather than a second copy of
             * 20 Mbps: it is the same number the user gets by tapping "High", so the two paths cannot
             * drift apart, and the fallback ladder already knows how to step down from it.
             */
            val AUTO_TRANSCODE_CEILING: Int = requireNotNull(PlaybackQuality.HIGH.maxStreamingBitrate)
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
 *   [MediaStream.isExternal] because that is the offline caller's own rule of thumb; neither online
 *   caller takes the default. Offline, a transcoded download side-loads a sidecar for an **embedded**
 *   subtitle the server extracted for it, and calling that track embedded would have it looked for
 *   among container groups the encode dropped. Online it is the delivery method that decides — see
 *   `sideLoadedSubtitle`, where an HLS rendition of a sidecar file is emphatically *not* side-loaded.
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
 * Whether this subtitle reaches ExoPlayer as a **source of its own** rather than out of the stream
 * it was handed — which is the question [MediaStream.toTrack]'s `sideLoaded` really asks, and it is
 * the server's chosen delivery that answers it, not whether the stream happens to be a file:
 *
 * - `EMBED` and `HLS` both arrive *inside* what the player opens — in the container, or as an
 *   `#EXT-X-MEDIA` rendition of the transcode's own master playlist. Both are matched by position,
 *   and an HLS-delivered **sidecar** is one of them, `MediaStream.isExternal` notwithstanding: a
 *   rendition carries no `external:<index>` id to match on.
 * - `EXTERNAL` is the side-loaded case proper, matched by that id.
 * - `ENCODE` is burned into the picture. Nothing selects it, and calling it side-loaded is what
 *   keeps it out of the positional count — a graphical subtitle the server had to burn in gets no
 *   rendition (Jellyfin only builds them for `IsTextSubtitleStream`), so counting it would push
 *   every text track after it onto the wrong rendition. Both lookups miss it, `selectSubtitle`
 *   answers `false`, and the ViewModel re-resolves — which is the only way to see it anyway.
 *
 * Anything else — `DROP`, or a server that sent no method at all — keeps the old default.
 */
private fun MediaStream.sideLoadedSubtitle(): Boolean =
    when (deliveryMethod) {
        SubtitleDeliveryMethod.EMBED, SubtitleDeliveryMethod.HLS -> false
        SubtitleDeliveryMethod.EXTERNAL, SubtitleDeliveryMethod.ENCODE -> true
        else -> isExternal
    }

/**
 * A subtitle stream ExoPlayer has to fetch separately, or `null` when it travels in the container.
 *
 * `SubtitleDeliveryMethod.EXTERNAL` covers both genuinely external files and subtitles the server
 * extracts on the fly while transcoding; either way the delivery URL and a MIME type ExoPlayer
 * understands are both required, so a PGS or DVB stream never becomes one of these.
 */
@Suppress("ReturnCount") // A sideloaded subtitle needs four fields from the SDK; any missing one drops the track.
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

/**
 * Small helper so callers can build a request from the route's string item id.
 *
 * `autoBitrate = true`: opening an item is the one moment nobody has picked a quality, which is
 * exactly what Auto means. The cap stays `null` here and is filled by [PlaybackInfoResolver].
 */
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
