package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
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
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.junit.jupiter.api.Test

/**
 * What `castTarget` changes about resolving, and it is exactly two things.
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

    // Never consulted here: none of these requests is an Auto one, and the cast branch would skip
    // the detector even if one were.
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
}
