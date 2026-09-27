package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.bitrate.AutoBitrateDetector
import dev.jellyboost.player.cast.CastConnection
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.deviceprofile.CastDeviceProfile
import dev.jellyboost.player.deviceprofile.CastReceiverClass
import dev.jellyboost.player.deviceprofile.DeviceCodecs
import dev.jellyboost.player.deviceprofile.DeviceProfileBuilder
import dev.jellyboost.player.deviceprofile.MediaCodecProbe
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.junit.jupiter.api.Test

/**
 * What `castTarget` changes about resolving, and it is exactly four things: the copy on disk is
 * skipped, the receiver's profile is the one sent, video stream copy is forbidden, and an Auto
 * transcode is walked back to High's rung without this device's link ever being measured.
 *
 * A new file rather than additions to [PlaybackSourceResolverTest] and [PlaybackInfoResolverTest]:
 * both of those state what the *local* pipeline does, and the regression gate is that they keep
 * saying it word for word.
 */
class PlaybackResolveCastTargetTest {
    private val local = mockk<LocalPlaybackResolver>()
    private val api = mockk<PlayerApi>()
    private val connectionState = mockk<ConnectionStateProvider>()

    private val deviceProfileBuilder =
        DeviceProfileBuilder(
            MediaCodecProbe { DeviceCodecs(videoCodecs = setOf("h264", "hevc"), audioCodecs = setOf("aac")) },
        )

    // Never consulted here, and deliberately unstubbed so a call would throw: the cast branch skips
    // the detector even for the Auto requests below.
    private val autoBitrateDetector = mockk<AutoBitrateDetector>()

    /** No session by default, so negotiations describe the conservative legacy receiver. */
    private val castStatus = CastStatusHolder()

    private val infoResolver = PlaybackInfoResolver(api, deviceProfileBuilder, autoBitrateDetector, castStatus)

    private val resolver = PlaybackSourceResolver(local, infoResolver, connectionState)

    private val request = PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, castTarget = true)

    @Test
    fun `a cast target streams from the server even when the film is on this device`() =
        runTest {
            every { connectionState.state } returns MutableStateFlow(ConnectionState.ONLINE)
            coEvery { local.resolve(any()) } returns PlayerFixtures.localSource()
            coEvery { api.getPlaybackInfo(any(), any()) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)))

            val result = resolver.resolve(request)

            // A `file://` URI is unreachable from a receiver, so rule 1 — "a completed download
            // always wins" — has to stand aside here as it does for `forceRemote`.
            result.shouldBeInstanceOf<AppResult.Success<*>>()
            result.value.shouldBeInstanceOf<RemotePlaybackMediaSource>()
            coVerify(exactly = 0) { local.resolve(any()) }
        }

    @Test
    fun `a cast negotiation is sent with the receiver's profile, not this device's`() =
        runTest {
            val sent = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)))

            infoResolver.resolve(request)

            sent.captured.deviceProfile!!.name shouldBe CastDeviceProfile.PROFILE_NAME
            // The probed profile claims HEVC on this "device"; the cast one must not, or the server
            // hands a receiver a stream it cannot decode.
            sent.captured.deviceProfile!!
                .directPlayProfiles
                .none { it.videoCodec?.contains("hevc") == true } shouldBe true
        }

    @Test
    fun `a receiver classified as 4K-capable is offered HEVC direct play`() =
        runTest {
            castStatus.setConnection(
                CastConnection.Connected(deviceName = "Living Room TV", receiver = CastReceiverClass.ULTRA_4K),
            )
            val sent = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)))

            infoResolver.resolve(request)

            // The request itself carries no receiver class — the negotiation reflects whatever the
            // coordinator resolved at session start.
            sent.captured.deviceProfile!!
                .directPlayProfiles
                .single { it.container == "mp4" && it.type == DlnaProfileType.VIDEO }
                .videoCodec!! shouldContain "hevc"
        }

    @Test
    fun `the quality cap still reaches the cast profile`() =
        runTest {
            val sent = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsTranscoding = true)))

            infoResolver.resolve(request.copy(maxStreamingBitrate = 4_000_000))

            sent.captured.deviceProfile!!.maxStreamingBitrate shouldBe 4_000_000
        }

    @Test
    fun `an ordinary request still gets this device's probed profile`() =
        runTest {
            val sent = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)))

            infoResolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            sent.captured.deviceProfile!!.name shouldBe DeviceProfileBuilder.PROFILE_NAME
        }

    // ---- no stream copy, and a transcode ceiling for Auto ----------------------------------------

    @Test
    fun `a cast negotiation forbids video stream copy, and a local one leaves it to the server`() =
        runTest {
            val sent = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)))

            infoResolver.resolve(request)
            infoResolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))

            // A stream-copied cast transcode has segments that stop matching its playlist once
            // ffmpeg restarts mid-file; the receiver trusts the playlist and buffers forever.
            sent.map { it.allowVideoStreamCopy } shouldBe listOf(false, null)
            // Audio copy is not this fix's business: the server's default stands for both.
            sent.map { it.allowAudioStreamCopy } shouldBe listOf(null, null)
        }

    @Test
    fun `a cast direct play is still offered when stream copy is forbidden`() =
        runTest {
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, any()) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)))

            val result = infoResolver.resolve(request)

            // The flag bounds a transcode's plan only; the server never consults it for direct play.
            result.shouldBeInstanceOf<AppResult.Success<RemotePlaybackMediaSource>>()
            result.value.playMethod shouldBe PlayMethod.DIRECT_PLAY
        }

    @Test
    fun `a cast auto transcode is re-negotiated at High's rung`() =
        runTest {
            val sent = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(transcodeOnlySource()))

            val result = infoResolver.resolve(request.copy(autoBitrate = true))

            // Pass 1 goes uncapped — the profile's own 120 Mbps, which measured as a 119.6 Mbps
            // `VideoBitrate` on the transcode URL — and is walked back to the 20 Mbps rung.
            sent.map { it.maxStreamingBitrate } shouldBe listOf(null, CEILING)
            sent.last().deviceProfile?.maxStreamingBitrate shouldBe CEILING
            sent.last().deviceProfile?.name shouldBe CastDeviceProfile.PROFILE_NAME
            sent.last().allowVideoStreamCopy shouldBe false
            result.shouldBeInstanceOf<AppResult.Success<RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe CEILING
            // Still Auto, so the next re-negotiation starts uncapped again.
            result.value.autoBitrate shouldBe true
            coVerify(exactly = 0) { autoBitrateDetector.currentCap() }
        }

    @Test
    fun `a cast auto direct play keeps the uncapped answer`() =
        runTest {
            val sent = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)))

            val result = infoResolver.resolve(request.copy(autoBitrate = true))

            // The file's own bytes, so no encoder to keep up; the receiver's link is not ours to cap.
            sent.single().maxStreamingBitrate shouldBe null
            result.shouldBeInstanceOf<AppResult.Success<RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe null
        }

    @Test
    fun `a hand-picked cast cap above the ceiling is transcoded at exactly what was asked for`() =
        runTest {
            val sent = mutableListOf<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(sent)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(transcodeOnlySource()))

            val result = infoResolver.resolve(request.copy(maxStreamingBitrate = ABOVE_CEILING_CAP))

            // The ceiling second-guesses Auto, never a person — on a television as on the tablet.
            sent.single().maxStreamingBitrate shouldBe ABOVE_CEILING_CAP
            result.shouldBeInstanceOf<AppResult.Success<RemotePlaybackMediaSource>>()
            result.value.maxStreamingBitrate shouldBe ABOVE_CEILING_CAP
        }

    @Test
    fun `a missing cap is over the ceiling for cast only, never for an unmeasured local transcode`() =
        runTest {
            // Its own resolver: the shared detector stays unstubbed so the cast cases prove it is
            // never asked.
            val unmeasured = mockk<AutoBitrateDetector> { coEvery { currentCap() } returns null }
            val localResolver = PlaybackInfoResolver(api, deviceProfileBuilder, unmeasured, castStatus)
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, any()) } returns
                PlayerFixtures.playbackInfoResponse(listOf(transcodeOnlySource()))

            localResolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, autoBitrate = true))

            // Locally, a measurement the detector could not make is its call, not this rule's.
            coVerify(exactly = 1) { api.getPlaybackInfo(any(), any()) }
        }

    /** A source the server will only transcode — both cheaper methods refused. */
    private fun transcodeOnlySource() =
        PlayerFixtures.mediaSourceInfo(
            transcodingUrl = "/videos/x/master.m3u8",
            transcodingSubProtocol = MediaStreamProtocol.HLS,
        )

    private companion object {
        /** `PlaybackQuality.HIGH`'s rung, spelled out here so the test pins the number, not the enum. */
        const val CEILING = 20_000_000

        const val ABOVE_CEILING_CAP = 64_000_000
    }
}
