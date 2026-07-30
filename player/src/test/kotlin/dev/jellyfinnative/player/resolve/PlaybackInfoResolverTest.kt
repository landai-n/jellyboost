package dev.jellyfinnative.player.resolve

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.api.PlayerApi
import dev.jellyfinnative.player.deviceprofile.DeviceCodecs
import dev.jellyfinnative.player.deviceprofile.DeviceProfileBuilder
import dev.jellyfinnative.player.deviceprofile.MediaCodecProbe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
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
 * The two things worth guarding here are the dash-less media-source-id quirk — which fails
 * silently and much later, as ignored stream indices — and the play-method decision, which is what
 * every URL and every report downstream keys off.
 */
class PlaybackInfoResolverTest {
    private val api = mockk<PlayerApi>()
    private val deviceProfileBuilder =
        DeviceProfileBuilder(
            MediaCodecProbe { DeviceCodecs(videoCodecs = setOf("h264"), audioCodecs = setOf("aac")) },
        )
    private val resolver = PlaybackInfoResolver(api, deviceProfileBuilder)

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

            // The server looks sources up by the dash-less form; with dashes it silently ignores
            // the stream indices instead of failing.
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
            (result.value as dev.jellyfinnative.player.model.RemotePlaybackMediaSource)
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

            // The server only reports supportsDirectPlay after checking the file against our
            // profile, so it outranks an also-offered transcoding URL.
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
            // DVB subtitles have no MIME type ExoPlayer accepts as a side-loaded source, so the
            // server has to burn them in or we do without them.
            val result =
                resolveWith(
                    PlayerFixtures.mediaSourceInfo(
                        supportsDirectPlay = true,
                        mediaStreams = listOf(PlayerFixtures.subtitleStream(index = 3, codec = "dvbsub")),
                    ),
                )

            result.externalSubtitles.shouldBeEmpty()
            // It is still offered in the picker — selecting it re-resolves and burns it in.
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

    // ---- M11: minting a play session for a file that is already on disk -------------------------

    @Test
    fun `minting asks for the item once and answers with the play session id`() =
        runTest {
            val request = slot<PlaybackInfoDto>()
            coEvery { api.getPlaybackInfo(PlayerFixtures.ITEM_ID, capture(request)) } returns
                PlayerFixtures.playbackInfoResponse(listOf(PlayerFixtures.mediaSourceInfo()))

            val minted = resolver.mintPlaySessionId(PlayerFixtures.ITEM_ID, mediaSourceId = null)

            minted shouldBe PlayerFixtures.PLAY_SESSION_ID
            request.captured.mediaSourceId shouldBe PlayerFixtures.DASHLESS_ITEM_ID
            // No profile to build a transcode plan from, and no live stream to allocate: the bytes
            // are already on the device and nothing here will ever fetch a URL.
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

            // The caller reports without a session id rather than not reporting at all; a group
            // member missing from the dashboard is the worse failure (M11, key decision 9).
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
    ): dev.jellyfinnative.player.model.RemotePlaybackMediaSource {
        coEvery { api.getPlaybackInfo(any(), any()) } returns PlayerFixtures.playbackInfoResponse(listOf(source))
        val result = resolver.resolve(request)
        result.shouldBeInstanceOf<AppResult.Success<dev.jellyfinnative.player.model.RemotePlaybackMediaSource>>()
        result.value.shouldNotBeNull()
        return result.value
    }
}
