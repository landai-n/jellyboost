package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.bitrate.AutoBitrateDetector
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.deviceprofile.DeviceCodecs
import dev.jellyboost.player.deviceprofile.DeviceProfileBuilder
import dev.jellyboost.player.deviceprofile.MediaCodecProbe
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.junit.jupiter.api.Test

/**
 * The video frame rate [PlaybackInfoResolver] carries onto the source: a cast transcode's HLS segment
 * grid is derived from it (`HlsSegmentSnap`), so "unknown" must mean unknown, never a guess. Its own
 * file because [PlaybackInfoResolverTest] is at detekt's `LargeClass` limit.
 */
class PlaybackInfoResolverFrameRateTest {
    private val api = mockk<PlayerApi>()
    private val resolver =
        PlaybackInfoResolver(
            api,
            DeviceProfileBuilder(
                MediaCodecProbe { DeviceCodecs(videoCodecs = setOf("h264"), audioCodecs = setOf("aac")) },
            ),
            mockk<AutoBitrateDetector> { coEvery { currentCap() } returns null },
            CastStatusHolder(),
        )

    @Test
    fun `carries the video stream's real frame rate, which a cast transcode's segment grid is derived from`() =
        runTest {
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams =
                            listOf(
                                PlayerFixtures.audioStream(index = 1),
                                PlayerFixtures.videoStream(
                                    index = 0,
                                    realFrameRate = 23.976025f,
                                    averageFrameRate = 24f,
                                ),
                            ),
                    ),
                )

            result.videoFrameRate shouldBe 23.976025f
        }

    @Test
    fun `falls back to the average frame rate, and knows none when the server reports nothing plausible`() =
        runTest {
            resolveWith(
                PlayerFixtures.mediaSourceInfo(
                    supportsDirectPlay = true,
                    mediaStreams = listOf(PlayerFixtures.videoStream(averageFrameRate = 25f)),
                ),
            ).videoFrameRate shouldBe 25f
            // The server itself distrusts an average read as 1000 fps.
            resolveWith(
                PlayerFixtures.mediaSourceInfo(
                    supportsDirectPlay = true,
                    mediaStreams = listOf(PlayerFixtures.videoStream(realFrameRate = 0f, averageFrameRate = 1000f)),
                ),
            ).videoFrameRate shouldBe null
            resolveWith(PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true)).videoFrameRate shouldBe null
        }

    private suspend fun resolveWith(source: MediaSourceInfo): RemotePlaybackMediaSource {
        coEvery { api.getPlaybackInfo(any(), any()) } returns PlayerFixtures.playbackInfoResponse(listOf(source))
        val result = resolver.resolve(PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID))
        result.shouldBeInstanceOf<AppResult.Success<RemotePlaybackMediaSource>>()
        return result.value
    }
}
