package dev.jellyfinnative.player.ui

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the audio and subtitle pickers offer for a **downloaded** item, and what a tap on one does.
 *
 * Its own class rather than more of [PlayerViewModelTest] because it is its own subject: every test
 * here is about the gap between what the item has and what the file on disk holds, and about the one
 * thing that decides whether that gap can be closed — the connection. The sequencing that class pins
 * is unchanged by all of it.
 *
 * The rule in one line: **online the picker offers the source's full track list and reaches anything
 * the file lacks by streaming the item; offline it offers only what the file can play.**
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerTrackPickerTest : PlayerViewModelFixture() {
    // ---- a track the file can play ---------------------------------------------------------------

    @Test
    fun `a track switch on a download is recorded on the local source`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            coEvery { resolver.resolve(any()) } returns
                AppResult.Success(
                    PlayerFixtures.localSource(
                        audioTracks =
                            listOf(
                                PlaybackTrack(index = 1, label = "English", language = "eng", codec = "ac3"),
                                PlaybackTrack(index = 2, label = "French", language = "fra", codec = "aac"),
                            ),
                        selectedAudioIndex = 1,
                    ),
                )
            val model = viewModel()
            advanceUntilIdle()

            model.selectAudioTrack(2)
            model.selectSubtitleTrack(3)
            advanceUntilIdle()

            model.uiState.value.selectedAudioIndex shouldBe 2
            model.uiState.value.selectedSubtitleIndex shouldBe 3
            // Locally satisfied, so nothing is renegotiated — same as a direct play online.
            coVerify(exactly = 1) { resolver.resolve(any()) }
        }

    // ---- offline: the backstop, for a tap the picker should never have offered --------------------

    @Test
    fun `offline, an audio track the local file cannot satisfy is refused instead of reloading it`() =
        runTest(dispatcher) {
            // Offline is the precondition: with a server to ask, the same tap streams the item
            // instead (see the connectivity-aware picker tests below).
            connection.value = ConnectionState.OFFLINE_NO_NETWORK
            playerHandle.trackSelectionSucceeds = false
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.localSource())
            val model = viewModel()
            advanceUntilIdle()

            model.selectAudioTrack(2)
            advanceUntilIdle()

            // Re-resolving a file:// URI runs the local resolver over the same file and returns the
            // same tracks: the switch still cannot be applied and playback has restarted for it.
            coVerify(exactly = 1) { resolver.resolve(any()) }
            playerHandle.prepared shouldHaveSize 1
            model.uiState.value.selectedAudioIndex
                .shouldBeNull()
            model.uiState.value.userMessage shouldBe PlayerMessage.TrackUnavailableOffline
        }

    @Test
    fun `offline, a subtitle the local file cannot satisfy is refused instead of reloading it`() =
        runTest(dispatcher) {
            connection.value = ConnectionState.OFFLINE_NO_NETWORK
            playerHandle.trackSelectionSucceeds = false
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.localSource())
            val model = viewModel()
            advanceUntilIdle()

            model.selectSubtitleTrack(6)
            advanceUntilIdle()

            coVerify(exactly = 1) { resolver.resolve(any()) }
            playerHandle.prepared shouldHaveSize 1
            model.uiState.value.selectedSubtitleIndex
                .shouldBeNull()
            model.uiState.value.userMessage shouldBe PlayerMessage.TrackUnavailableOffline
        }

    @Test
    fun `a streamed item still re-requests a track the current stream cannot satisfy`() =
        runTest(dispatcher) {
            // The offline guard must not reach the online path, where a re-resolve is the whole
            // mechanism for getting a different audio track out of a transcode.
            playerHandle.trackSelectionSucceeds = false
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.selectAudioTrack(2)
            advanceUntilIdle()

            requests.last().audioStreamIndex shouldBe 2
            requests.last().maxStreamingBitrate.shouldBeNull()
            // Nothing to bypass: there is no download of this item to be forced past.
            requests.last().forceRemote shouldBe false
        }

    // ---- the picker follows the connection -------------------------------------------------------

    @Test
    fun `online, a downloaded item's pickers offer every track the item has`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())

            val model = viewModel()
            advanceUntilIdle()

            // The transcode baked in one audio track and two sidecars; with a server to ask, the
            // picker is still the item's own list.
            model.uiState.value.audioTracks
                .map { it.index } shouldContainExactly listOf(3, 4, 5)
            model.uiState.value.subtitleTracks
                .map { it.index } shouldContainExactly listOf(0, 1, 6, 7)
        }

    @Test
    fun `offline, a downloaded item's pickers offer only what the file can play`() =
        runTest(dispatcher) {
            connection.value = ConnectionState.OFFLINE_NO_NETWORK
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())

            val model = viewModel()
            advanceUntilIdle()

            // A row that cannot do anything is worse than one fewer language.
            model.uiState.value.audioTracks
                .map { it.index } shouldContainExactly listOf(3)
            model.uiState.value.subtitleTracks
                .map { it.index } shouldContainExactly listOf(0, 1)
        }

    @Test
    fun `losing the network while the picker is open withdraws the tracks it can no longer reach`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.audioTracks shouldHaveSize 3

            connection.value = ConnectionState.OFFLINE_NO_NETWORK
            advanceUntilIdle()

            // The sheet is a `PlayerUiState` read, so it redraws with the shorter list rather than
            // leaving a row that would now be refused.
            model.uiState.value.audioTracks
                .map { it.index } shouldContainExactly listOf(3)
            model.uiState.value.subtitleTracks
                .map { it.index } shouldContainExactly listOf(0, 1)

            connection.value = ConnectionState.ONLINE
            advanceUntilIdle()

            model.uiState.value.audioTracks shouldHaveSize 3
        }

    @Test
    fun `online, a track the download lacks is streamed from the server at the current position`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = true)
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.selectAudioTrack(PlayerFixtures.STREAMED_AUDIO_INDEX)
            advanceUntilIdle()

            // Without the flag the re-resolve returns the same file and the same one audio track —
            // the original bug. With it the server is asked, and the film picks up where it was.
            requests.last().forceRemote shouldBe true
            requests.last().audioStreamIndex shouldBe PlayerFixtures.STREAMED_AUDIO_INDEX
            requests.last().startPositionTicks shouldBe 300_000_000L
            model.uiState.value.isLocalPlayback shouldBe false
            model.uiState.value.userMessage shouldBe PlayerMessage.StreamingForTrackChange
        }

    @Test
    fun `online, a subtitle the download lacks is streamed from the server`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.selectSubtitleTrack(PlayerFixtures.STREAMED_SUBTITLE_INDEX)
            advanceUntilIdle()

            requests.last().forceRemote shouldBe true
            requests.last().subtitleStreamIndex shouldBe PlayerFixtures.STREAMED_SUBTITLE_INDEX
        }

    @Test
    fun `a track the file does hold is still applied without going anywhere near the server`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
            val model = viewModel()
            advanceUntilIdle()

            model.selectAudioTrack(PlayerFixtures.BAKED_AUDIO_INDEX)
            advanceUntilIdle()

            coVerify(exactly = 1) { resolver.resolve(any()) }
            model.uiState.value.selectedAudioIndex shouldBe PlayerFixtures.BAKED_AUDIO_INDEX
        }

    @Test
    fun `choosing a track the file does hold while streaming it goes back to the download`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            val model = streamingDownloadedItem()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(PlayerFixtures.downloadedFilm())

            model.selectAudioTrack(PlayerFixtures.BAKED_AUDIO_INDEX)
            advanceUntilIdle()

            // The file can serve this one, so there is no reason to keep spending bandwidth on it —
            // and playing off the disk is what survives the network dropping.
            requests.last().forceRemote shouldBe false
            model.uiState.value.isLocalPlayback shouldBe true
        }

    @Test
    fun `changing quality while streaming a downloaded item does not drop back to the file`() =
        runTest(dispatcher) {
            val model = streamingDownloadedItem()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(source.copy(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = 3_000_000))

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // Forgetting the flag here would silently return the item to the local file and lose
            // the very track the user went to the server for.
            requests.last().forceRemote shouldBe true
            requests.last().maxStreamingBitrate shouldBe 3_000_000
        }

    @Test
    fun `a server that turns out not to be there returns the item to its file instead of failing`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            val local = PlayerFixtures.downloadedFilm()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(local)
            val model = viewModel()
            advanceUntilIdle()

            // The network died between the picker being drawn and the request going out — or it was
            // the server, which the connection state cannot know until a probe says so.
            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } coAnswers {
                if (requests.last().forceRemote) AppResult.Failure(AppError.Network()) else AppResult.Success(local)
            }

            model.selectAudioTrack(PlayerFixtures.STREAMED_AUDIO_INDEX)
            advanceUntilIdle()

            requests.map { it.forceRemote } shouldContainExactly listOf(true, false)
            model.uiState.value.errorMessage
                .shouldBeNull()
            model.uiState.value.isLocalPlayback shouldBe true
            model.uiState.value.userMessage shouldBe PlayerMessage.TrackUnavailableOffline
        }

    /**
     * A session that started on the downloaded file and is now streaming it for a track the file
     * does not hold — the state every "while streaming a download" test starts from.
     */
    private fun TestScope.streamingDownloadedItem(): PlayerViewModel {
        val handle = playerHandle.trackSelectionSucceeds
        playerHandle.trackSelectionSucceeds = false
        coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
        val model = viewModel()
        advanceUntilIdle()

        coEvery { resolver.resolve(any()) } returns AppResult.Success(source)
        model.selectAudioTrack(PlayerFixtures.STREAMED_AUDIO_INDEX)
        advanceUntilIdle()

        playerHandle.trackSelectionSucceeds = handle
        return model
    }
}
