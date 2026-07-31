package dev.jellyboost.player.ui

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.resolve.PlaybackResolveRequest
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
    fun `a direct-played stream of a download still goes home for a track the file holds`() =
        runTest(dispatcher) {
            // The M10 device finding (check B.3): the server was direct-playing the original file,
            // so the stream carried every track and the in-stream switch *succeeded* — which is
            // exactly why it must not be offered one. Only the transcoded case reached the
            // re-resolve, which is why the transcoded walk above looked fine.
            playerHandle.trackSelectionSucceeds = true
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = true)
            val model = streamingDownloadedItem()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(PlayerFixtures.downloadedFilm())

            model.selectAudioTrack(PlayerFixtures.BAKED_AUDIO_INDEX)
            advanceUntilIdle()

            requests.last().forceRemote shouldBe false
            requests.last().audioStreamIndex shouldBe PlayerFixtures.BAKED_AUDIO_INDEX
            // The film picks up where it was, exactly as the outbound trip did.
            requests.last().startPositionTicks shouldBe 300_000_000L
            model.uiState.value.isLocalPlayback shouldBe true
            // The player was never offered the switch: taking it would have stranded the session on
            // the network with the bytes already on the disk.
            playerHandle.selectedAudioIndices shouldContainExactly listOf(PlayerFixtures.STREAMED_AUDIO_INDEX)
        }

    @Test
    fun `after going home the item is a download again, not a stream it is stuck on`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            val model = streamingDownloadedItem()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
            model.selectAudioTrack(PlayerFixtures.BAKED_AUDIO_INDEX)
            advanceUntilIdle()

            // Leaving the file again has to be a fresh decision, not a flag left standing.
            playerHandle.trackSelectionSucceeds = false
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source)

            model.selectAudioTrack(PlayerFixtures.STREAMED_AUDIO_INDEX)
            advanceUntilIdle()

            // This copy is only reachable from a source the ViewModel considers local.
            model.uiState.value.userMessage shouldBe PlayerMessage.StreamingForTrackChange
            model.uiState.value.isLocalPlayback shouldBe false
        }

    @Test
    fun `while streaming a download, another track the file lacks is switched in the stream`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            val model = streamingDownloadedItem()

            // Index 4 is the second server-only audio track: the file cannot serve it either, so
            // there is nowhere to go home to and a reopen would buy nothing but a black frame.
            model.selectAudioTrack(SECOND_STREAMED_AUDIO_INDEX)
            advanceUntilIdle()

            coVerify(exactly = 2) { resolver.resolve(any()) }
            model.uiState.value.selectedAudioIndex shouldBe SECOND_STREAMED_AUDIO_INDEX
            model.uiState.value.isLocalPlayback shouldBe false
        }

    @Test
    fun `a subtitle the file holds takes a direct-played stream of a download home`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            val model = streamingDownloadedItem(streamed = streamedForSubtitle, pick = pickStreamedSubtitle)

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(PlayerFixtures.downloadedFilm())

            model.selectSubtitleTrack(SIDECAR_SUBTITLE_INDEX)
            advanceUntilIdle()

            requests.last().forceRemote shouldBe false
            requests.last().subtitleStreamIndex shouldBe SIDECAR_SUBTITLE_INDEX
            model.uiState.value.isLocalPlayback shouldBe true
            playerHandle.selectedSubtitleIndices shouldContainExactly listOf(PlayerFixtures.STREAMED_SUBTITLE_INDEX)
        }

    @Test
    fun `while streaming a download, another subtitle the file lacks is switched in the stream`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            val model = streamingDownloadedItem(streamed = streamedForSubtitle, pick = pickStreamedSubtitle)

            // Index 6 is embedded in the source and its sidecar was never fetched.
            model.selectSubtitleTrack(SECOND_STREAMED_SUBTITLE_INDEX)
            advanceUntilIdle()

            coVerify(exactly = 2) { resolver.resolve(any()) }
            model.uiState.value.selectedSubtitleIndex shouldBe SECOND_STREAMED_SUBTITLE_INDEX
            model.uiState.value.isLocalPlayback shouldBe false
        }

    @Test
    fun `turning subtitles off does not drag a stream home away from the audio it went for`() =
        runTest(dispatcher) {
            // The file trivially "holds" no subtitles, but it cannot hold the audio this session
            // left for — so going home would silently undo the switch the user asked for.
            playerHandle.trackSelectionSucceeds = true
            val model = streamingDownloadedItem()

            model.selectSubtitleTrack(null)
            advanceUntilIdle()

            coVerify(exactly = 2) { resolver.resolve(any()) }
            model.uiState.value.isLocalPlayback shouldBe false
            model.uiState.value.selectedAudioIndex shouldBe PlayerFixtures.STREAMED_AUDIO_INDEX
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
     *
     * @param streamed what the server hands back, carrying the selection it was asked for exactly as
     *   a real `PlaybackInfo` answer echoes it. That echo is not decoration: a later track change
     *   weighs *both* selections when it decides whether the file could take the whole session back,
     *   so a stream that has forgotten what it was opened for would answer the wrong question.
     * @param pick the tap that left the file.
     */
    private fun TestScope.streamingDownloadedItem(
        streamed: RemotePlaybackMediaSource = source.copy(selectedAudioIndex = PlayerFixtures.STREAMED_AUDIO_INDEX),
        pick: PlayerViewModel.() -> Unit = { selectAudioTrack(PlayerFixtures.STREAMED_AUDIO_INDEX) },
    ): PlayerViewModel {
        val handle = playerHandle.trackSelectionSucceeds
        playerHandle.trackSelectionSucceeds = false
        coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
        val model = viewModel()
        advanceUntilIdle()

        coEvery { resolver.resolve(any()) } returns AppResult.Success(streamed)
        model.pick()
        advanceUntilIdle()

        playerHandle.trackSelectionSucceeds = handle
        return model
    }

    private companion object {
        /** The source's other server-only audio track; the download baked in neither. */
        const val SECOND_STREAMED_AUDIO_INDEX = 4

        /** An embedded subtitle of the source whose sidecar was never fetched. */
        const val SECOND_STREAMED_SUBTITLE_INDEX = 6

        /** A subtitle the download did fetch as a sidecar — the file can serve it alone. */
        const val SIDECAR_SUBTITLE_INDEX = 1
    }

    /**
     * The stream a session that left the file for a *subtitle* lands on: the audio is still the one
     * baked into the download, and only the subtitle is server-only.
     */
    private val streamedForSubtitle
        get() =
            source.copy(
                selectedAudioIndex = PlayerFixtures.BAKED_AUDIO_INDEX,
                selectedSubtitleIndex = PlayerFixtures.STREAMED_SUBTITLE_INDEX,
            )

    private val pickStreamedSubtitle: PlayerViewModel.() -> Unit =
        { selectSubtitleTrack(PlayerFixtures.STREAMED_SUBTITLE_INDEX) }
}
