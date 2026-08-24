package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.bitrate.AutoBitrateDetector
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.deviceprofile.DeviceCodecs
import dev.jellyboost.player.deviceprofile.DeviceProfileBuilder
import dev.jellyboost.player.deviceprofile.MediaCodecProbe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlaybackInfoResolver].
 *
 * Guards two things: the dash-less media-source-id quirk (fails silently, later, as ignored
 * stream indices) and the play-method decision, which every downstream URL and report keys off.
 */
class PlaybackInfoResolverTest {
    private val api = mockk<PlayerApi>()
    private val deviceProfileBuilder =
        DeviceProfileBuilder(
            MediaCodecProbe { DeviceCodecs(videoCodecs = setOf("h264"), audioCodecs = setOf("aac")) },
        )
    private val autoBitrateDetector =
        mockk<AutoBitrateDetector> {
            coEvery { currentCap() } returns MEASURED_CAP
        }
    private val resolver = PlaybackInfoResolver(api, deviceProfileBuilder, autoBitrateDetector, CastStatusHolder())

    // ---- the dash-less media source id --------------------------------------------------------

    @Test
    fun `sends the item id without dashes when no media source was named`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                )

            resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            // Dashes would silently drop the stream indices instead of failing outright.
            request.captured.mediaSourceId shouldBe PlayerFixtures.DASHLESS_ITEM_ID
        }

    @Test
    fun `passes an explicitly chosen media source id through untouched`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(id = "second-file", supportsDirectPlay = true)),
                )

            resolver.resolve(
                PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, mediaSourceId = "second-file"),
            )

            request.captured.mediaSourceId shouldBe "second-file"
        }

    @Test
    fun `matches the returned source even though the server answers with dashes`() =
        runTest {
            val other = PlayerFixtures.mediaSourceInfo(id = "aaaaaaaa-0000-0000-0000-000000000000")
            val wanted =
                PlayerFixtures.mediaSourceInfo(
                    id = PlayerFixtures.ITEM_ID.toString(),
                    supportsDirectPlay = true,
                )
            coEvery { api.getPlaybackInfo(any(), any()) } returns
                PlayerFixtures.playbackInfoResponse(listOf(other, wanted))

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            (result.value as dev.jellyboost.player.model.RemotePlaybackMediaSource)
                .mediaSourceId shouldBe PlayerFixtures.ITEM_ID.toString()
        }

    // ---- the play-method decision matrix ------------------------------------------------------

    @Test
    fun `direct play wins when the server says the file is playable as is`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        supportsDirectStream = true,
                        supportsTranscoding = true,
                        transcodingUrl = "/videos/x/master.m3u8",
                    ),
                )

            // supportsDirectPlay is checked server-side against our profile, so it outranks a
            // transcoding URL offered alongside it.
            result.playMethod shouldBe PlayMethod.DIRECT_PLAY
        }

    @Test
    fun `direct stream is used when only the container needs changing`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(supportsDirectStream = true, supportsTranscoding = true),
                )

            result.playMethod shouldBe PlayMethod.DIRECT_STREAM
        }

    @Test
    fun `a transcoding url with both cheaper options refused means transcode`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        transcodingUrl = "/videos/x/master.m3u8",
                        transcodingSubProtocol = MediaStreamProtocol.HLS,
                    ),
                )

            result.playMethod shouldBe PlayMethod.TRANSCODE
            result.transcodingUrl shouldBe "/videos/x/master.m3u8"
        }

    @Test
    fun `a source that supports nothing at all is a failure, not a guess`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo()))

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            result.shouldBeInstanceOf<AppResult.Failure>()
        }

    // ---- request parameters -------------------------------------------------------------------

    @Test
    fun `sends a resume position but omits a zero one`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                )

            resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, startPositionTicks = 600L))
            request.captured.startTimeTicks shouldBe 600L

            resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))
            request.captured.startTimeTicks.shouldBeNull()
        }

    @Test
    fun `carries the bitrate cap into both the request and the device profile`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                )

            resolver.resolve(
                PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, maxStreamingBitrate = 3_000_000),
            )

            request.captured.maxStreamingBitrate shouldBe 3_000_000
            request.captured.deviceProfile
                ?.maxStreamingBitrate shouldBe 3_000_000
        }

    // ---- Auto's measured cap ------------------------------------------------------------------

    @Test
    fun `an auto request is negotiated at the measured bitrate, profile included`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                )

            val result =
                resolver.resolve(
                    PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, autoBitrate = true),
                )

            request.captured.maxStreamingBitrate shouldBe MEASURED_CAP
            request.captured.deviceProfile
                ?.maxStreamingBitrate shouldBe MEASURED_CAP
            result.shouldBeInstanceOf<AppResult.Success<dev.jellyboost.player.model.RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe MEASURED_CAP
            result.value.autoBitrate shouldBe true
        }

    @Test
    fun `a cast auto request is still sent uncapped`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                )

            resolver.resolve(
                PlaybackResolveRequest(
                    itemId = PlayerFixtures.ITEM_ID,
                    autoBitrate = true,
                    castTarget = true,
                ),
            )

            // The receiver decides what it can handle, not this device — no cap is sent.
            request.captured.maxStreamingBitrate.shouldBeNull()
            coVerify(exactly = 0) { autoBitrateDetector.currentCap() }
        }

    @Test
    fun `a hand-picked cap is never second-guessed by a measurement`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                )

            resolver.resolve(
                PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, maxStreamingBitrate = 3_000_000),
            )

            request.captured.maxStreamingBitrate shouldBe 3_000_000
            coVerify(exactly = 0) { autoBitrateDetector.currentCap() }
        }

    // ---- Auto's cap is not allowed to be a transcode's target --------------------------------------

    @Test
    fun `an auto transcode above High's rung is re-negotiated at it`() =
        runTest {
            coEvery { autoBitrateDetector.currentCap() } returns ABOVE_CEILING_CAP
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(transcodeOnlySource()))

            val result =
                resolver.resolve(
                    PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, autoBitrate = true),
                )

            // The cap also targets the transcode; at the link's own ceiling it can't encode in
            // realtime (0.76x measured), so pass 1 is abandoned and renegotiated at High's rung.
            coVerify(exactly = 2) { api.getPlaybackInfo(any(), any()) }
            requests.map { it.maxStreamingBitrate } shouldBe listOf(ABOVE_CEILING_CAP, CEILING)
            requests.last().deviceProfile?.maxStreamingBitrate shouldBe CEILING
            result.shouldBeInstanceOf<AppResult.Success<dev.jellyboost.player.model.RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe CEILING
            // Still Auto — the measurement was overruled, not the user's choice.
            result.value.autoBitrate shouldBe true
        }

    @Test
    fun `a direct play keeps the full measured cap however high it is`() =
        runTest {
            coEvery { autoBitrateDetector.currentCap() } returns ABOVE_CEILING_CAP
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                )

            val result =
                resolver.resolve(
                    PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, autoBitrate = true),
                )

            // Direct play is the one case a high cap pays for: original bytes, no re-encode to
            // keep up with.
            coVerify(exactly = 1) { api.getPlaybackInfo(any(), any()) }
            result.shouldBeInstanceOf<AppResult.Success<dev.jellyboost.player.model.RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe ABOVE_CEILING_CAP
        }

    @Test
    fun `an auto transcode already under the ceiling is negotiated once`() =
        runTest {
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(transcodeOnlySource()))

            val result =
                resolver.resolve(
                    PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, autoBitrate = true),
                )

            // MEASURED_CAP is below High's rung, so there is nothing to walk back.
            coVerify(exactly = 1) { api.getPlaybackInfo(any(), any()) }
            result.shouldBeInstanceOf<AppResult.Success<dev.jellyboost.player.model.RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe MEASURED_CAP
        }

    @Test
    fun `a hand-picked cap above the ceiling is transcoded at exactly what was asked for`() =
        runTest {
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(transcodeOnlySource()))

            val result =
                resolver.resolve(
                    PlaybackResolveRequest(
                        itemId = PlayerFixtures.ITEM_ID,
                        maxStreamingBitrate = ABOVE_CEILING_CAP,
                    ),
                )

            // The ceiling second-guesses a *measurement*, never a person.
            coVerify(exactly = 1) { api.getPlaybackInfo(any(), any()) }
            requests.single().maxStreamingBitrate shouldBe ABOVE_CEILING_CAP
            result.shouldBeInstanceOf<AppResult.Success<dev.jellyboost.player.model.RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe ABOVE_CEILING_CAP
        }

    @Test
    fun `forwards the direct play and direct stream vetoes that force a transcode`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(
                        PlayerFixtures.mediaSourceInfo(
                            transcodingUrl = "/videos/x/master.m3u8",
                            transcodingSubProtocol = MediaStreamProtocol.HLS,
                        ),
                    ),
                )

            resolver.resolve(
                PlaybackResolveRequest(
                    itemId = PlayerFixtures.ITEM_ID,
                    enableDirectPlay = false,
                    enableDirectStream = false,
                ),
            )

            request.captured.enableDirectPlay shouldBe false
            request.captured.enableDirectStream shouldBe false
        }

    // ---- streams ------------------------------------------------------------------------------

    @Test
    fun `maps audio and subtitle streams onto selectable tracks`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams =
                            listOf(
                                PlayerFixtures.audioStream(index = 1, displayTitle = "English - AC3"),
                                PlayerFixtures.audioStream(index = 2, language = "fra", displayTitle = "French"),
                                PlayerFixtures.subtitleStream(index = 3),
                            ),
                        defaultAudioStreamIndex = 2,
                    ),
                )

            result.audioTracks.map { it.index } shouldBe listOf(1, 2)
            result.audioTracks
                .first { it.index == 2 }
                .isDefault shouldBe true
            result.subtitleTracks.map { it.index } shouldBe listOf(3)
            result.selectedAudioIndex shouldBe 2
        }

    @Test
    fun `side-loads external text subtitles with their delivery url`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams = listOf(PlayerFixtures.subtitleStream(index = 3, codec = "srt")),
                    ),
                )

            val subtitle = result.externalSubtitles.single()
            subtitle.index shouldBe 3
            subtitle.url shouldBe "/Videos/1/Subtitles/3/Stream.srt"
            subtitle.mimeType shouldBe "application/x-subrip"
        }

    @Test
    fun `does not side-load a subtitle format ExoPlayer has no decoder for`() =
        runTest {
            // DVB subtitles have no ExoPlayer-accepted side-load MIME type — burn-in or nothing.
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams = listOf(PlayerFixtures.subtitleStream(index = 3, codec = "dvbsub")),
                    ),
                )

            result.externalSubtitles.shouldBeEmpty()
            // Still offered in the picker — selecting it re-resolves and burns it in.
            result.subtitleTracks.map { it.index } shouldBe listOf(3)
        }

    @Test
    fun `does not side-load an embedded subtitle`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams =
                            listOf(
                                PlayerFixtures.subtitleStream(
                                    index = 3,
                                    deliveryMethod = SubtitleDeliveryMethod.EMBED,
                                    deliveryUrl = null,
                                    isExternal = false,
                                ),
                            ),
                    ),
                )

            result.externalSubtitles.shouldBeEmpty()
        }

    @Test
    fun `marks an external delivery as side-loaded even for a stream inside the container`() =
        runTest {
            // The server extracts an embedded subrip and hands back a delivery URL, so
            // `MediaStream.isExternal` is false even though ExoPlayer still opens it as its own source.
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams =
                            listOf(PlayerFixtures.subtitleStream(index = 3, isExternal = false)),
                    ),
                )

            result.subtitleTracks
                .single()
                .isExternal shouldBe true
        }

    @Test
    fun `a burned-in subtitle is never counted among the tracks the stream carries`() =
        runTest {
            // A burned-in subtitle gets no HLS rendition, so it must not occupy a slot in the
            // positional count `TrackSelectionController` runs over the renditions that do exist.
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams =
                            listOf(
                                PlayerFixtures.subtitleStream(
                                    index = 3,
                                    codec = "pgssub",
                                    deliveryMethod = SubtitleDeliveryMethod.ENCODE,
                                    deliveryUrl = null,
                                    isExternal = false,
                                ),
                            ),
                    ),
                )

            result.subtitleTracks
                .single()
                .isExternal shouldBe true
            result.externalSubtitles.shouldBeEmpty()
        }

    @Test
    fun `an HLS rendition is a track the stream carries, never a side-load`() =
        runTest {
            // Cues ride in the transcode's own master playlist and share its `TimestampAdjuster`,
            // matched by position — never external, whatever the stream says.
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        transcodingUrl = TRANSCODING_URL,
                        mediaStreams =
                            listOf(
                                PlayerFixtures.subtitleStream(
                                    index = 3,
                                    deliveryMethod = SubtitleDeliveryMethod.HLS,
                                    deliveryUrl = null,
                                    isExternal = true,
                                ),
                            ),
                    ),
                )

            result.subtitleTracks
                .single()
                .isExternal shouldBe false
            result.externalSubtitles.shouldBeEmpty()
        }

    @Test
    fun `treats a subtitle index of minus one as subtitles off`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true, defaultSubtitleStreamIndex = 3),
                    request =
                        PlaybackResolveRequest(
                            itemId = PlayerFixtures.ITEM_ID,
                            subtitleStreamIndex = -1,
                        ),
                )

            // -1 must not fall back to the item's default, which is what `null` means.
            result.selectedSubtitleIndex.shouldBeNull()
        }

    // ---- the second pass: in-manifest subtitles for a transcode --------------------------------

    @Test
    fun `re-asks a transcode for HLS renditions when its text subtitles would be side-loaded`() =
        runTest {
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returnsMany
                listOf(
                    PlayerFixtures.playbackInfoResponse(listOf(sideLoadingTranscode())),
                    PlayerFixtures.playbackInfoResponse(listOf(renditionTranscode())),
                )

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            requests.size shouldBe 2
            // Pass 1's answer decides whether pass 2's round trip is worth taking.
            requests[0].subtitleProfiles().any { it.method == SubtitleDeliveryMethod.EXTERNAL } shouldBe true
            requests[1].subtitleProfiles().none { it.method == SubtitleDeliveryMethod.EXTERNAL } shouldBe true
            requests[1].subtitleProfiles().any {
                it.format == "vtt" && it.method == SubtitleDeliveryMethod.HLS
            } shouldBe true

            val source = result.value as dev.jellyboost.player.model.RemotePlaybackMediaSource
            source.externalSubtitles.shouldBeEmpty()
            source.subtitleTracks.map { it.isExternal } shouldBe listOf(false)
        }

    @Test
    fun `does not re-ask a direct play`() =
        runTest {
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(
                        PlayerFixtures.mediaSourceInfo(
                            supportsDirectPlay = true,
                            mediaStreams = listOf(PlayerFixtures.subtitleStream(index = 3)),
                        ),
                    ),
                )

            resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            // A direct-played file sent the rendition profile would negotiate `Encode` instead —
            // a burn-in transcode of something that needed none.
            requests.size shouldBe 1
        }

    @Test
    fun `does not re-ask a transcode that side-loads nothing`() =
        runTest {
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(renditionTranscode()))

            resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            requests.size shouldBe 1
        }

    @Test
    fun `does not re-ask for a cast receiver`() =
        runTest {
            val requests = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(requests)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(sideLoadingTranscode()))

            resolver.resolve(
                PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, castTarget = true),
            )

            // The receiver's subtitle handling is its own; `CastSpecMapper` builds tracks from
            // the side-loaded list.
            requests.size shouldBe 1
        }

    @Test
    fun `keeps the side-loaded answer when the second pass stops transcoding`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } returnsMany
                listOf(
                    PlayerFixtures.playbackInfoResponse(listOf(sideLoadingTranscode())),
                    PlayerFixtures.playbackInfoResponse(
                        listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                    ),
                )

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            val source = result.value as dev.jellyboost.player.model.RemotePlaybackMediaSource
            source.playMethod shouldBe PlayMethod.TRANSCODE
            source.externalSubtitles.map { it.index } shouldBe listOf(3)
        }

    @Test
    fun `keeps the side-loaded answer when the second pass fails outright`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } returns
                PlayerFixtures.playbackInfoResponse(listOf(sideLoadingTranscode())) andThenThrows
                TimeoutException("slow", null)

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            // A playable stream with drifting cues beats an error screen.
            result.shouldBeInstanceOf<AppResult.Success<*>>()
            (result.value as dev.jellyboost.player.model.RemotePlaybackMediaSource)
                .externalSubtitles
                .map { it.index } shouldBe listOf(3)
        }

    // ---- failures -----------------------------------------------------------------------------

    @Test
    fun `a response without a play session id cannot be played`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } returns
                PlayerFixtures.playbackInfoResponse(
                    sources = listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)),
                    playSessionId = null,
                )

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.NotFound>()
        }

    @Test
    fun `folds a timeout into a network error`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } throws TimeoutException("slow", null)

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.Network>()
        }

    @Test
    fun `folds a 401 into an unauthorized error so the session layer can react`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } throws InvalidStatusException(401)

            val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.Unauthorized>()
        }

    // ---- minting a play session for a file that is already on disk ---------------------------------

    @Test
    fun `minting asks for the item once and answers with the play session id`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo()))

            val minted = resolver.mintPlaySessionId(PlayerFixtures.ITEM_ID, mediaSourceId = null)

            minted shouldBe PlayerFixtures.PLAY_SESSION_ID
            request.captured.mediaSourceId shouldBe PlayerFixtures.DASHLESS_ITEM_ID
            // No profile to build a transcode plan from, no live stream to allocate — the bytes
            // are already on the device.
            request.captured.deviceProfile.shouldBeNull()
            request.captured.autoOpenLiveStream shouldBe false
        }

    @Test
    fun `minting sends a named media source without its dashes too`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(any(), capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo()))

            resolver.mintPlaySessionId(PlayerFixtures.ITEM_ID, mediaSourceId = PlayerFixtures.ITEM_ID.toString())

            request.captured.mediaSourceId shouldBe PlayerFixtures.DASHLESS_ITEM_ID
        }

    @Test
    fun `a failed mint is null rather than an exception`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } throws TimeoutException("slow", null)

            // Reports without a session id rather than not reporting — a missing group member is
            // the worse failure.
            resolver.mintPlaySessionId(PlayerFixtures.ITEM_ID, mediaSourceId = null).shouldBeNull()
        }

    @Test
    fun `a server that answers without a play session id mints nothing`() =
        runTest {
            coEvery { api.getPlaybackInfo(any(), any()) } returns
                PlayerFixtures.playbackInfoResponse(
                    listOf(PlayerFixtures.mediaSourceInfo()),
                    playSessionId = null,
                )

            resolver.mintPlaySessionId(PlayerFixtures.ITEM_ID, mediaSourceId = null).shouldBeNull()
        }

    private suspend fun resolveWith(
        source: org.jellyfin.sdk.model.api.MediaSourceInfo,
        request: PlaybackResolveRequest = PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID),
    ): dev.jellyboost.player.model.RemotePlaybackMediaSource {
        coEvery { api.getPlaybackInfo(any(), any()) } returns PlayerFixtures.playbackInfoResponse(listOf(source))
        val result = resolver.resolve(request)
        result.shouldBeInstanceOf<AppResult.Success<dev.jellyboost.player.model.RemotePlaybackMediaSource>>()
        result.value.shouldNotBeNull()
        return result.value
    }

    /** A source the server will only transcode — both cheaper methods refused. */
    private fun transcodeOnlySource() =
        PlayerFixtures.mediaSourceInfo(
            transcodingUrl = "/videos/x/master.m3u8",
            transcodingSubProtocol = MediaStreamProtocol.HLS,
        )

    /** What pass 1 gets back today: a transcode whose one text subtitle is a side-loaded file. */
    private fun sideLoadingTranscode() =
        PlayerFixtures.mediaSourceInfo(
            transcodingUrl = TRANSCODING_URL,
            mediaStreams = listOf(PlayerFixtures.subtitleStream(index = 3, isExternal = false)),
        )

    /** What pass 2 gets back: the same transcode with the subtitle in the master playlist. */
    private fun renditionTranscode() =
        PlayerFixtures.mediaSourceInfo(
            transcodingUrl = TRANSCODING_URL,
            mediaStreams =
                listOf(
                    PlayerFixtures.subtitleStream(
                        index = 3,
                        deliveryMethod = SubtitleDeliveryMethod.HLS,
                        deliveryUrl = null,
                        isExternal = false,
                    ),
                ),
        )

    private fun PlaybackInfoDto.subtitleProfiles() = deviceProfile?.subtitleProfiles.orEmpty()

    private companion object {
        /** With `SubtitleMethod=Hls` appended by the server once pass 2 wins; unused by the test. */
        const val TRANSCODING_URL = "/videos/x/master.m3u8"

        /** Deliberately not one of `PlaybackQuality`'s rungs: a measurement lands where it lands. */
        const val MEASURED_CAP = 5_600_000

        /** A measurement on a fast link — the shape of the one that stalled the first device walk. */
        const val ABOVE_CEILING_CAP = 64_000_000

        /** `PlaybackQuality.HIGH`'s rung, spelled out here so the test pins the number, not the enum. */
        const val CEILING = 20_000_000
    }
}
