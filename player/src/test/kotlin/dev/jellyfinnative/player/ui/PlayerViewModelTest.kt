package dev.jellyfinnative.player.ui

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.PlaybackException
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.fallback.DecoderFallbackHandler
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.ExoMediaSourceFactory
import dev.jellyfinnative.player.resolve.PlaybackInfoResolver
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.session.FakePlayerHandle
import dev.jellyfinnative.player.session.PlayerEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlayerViewModel].
 *
 * The player's hard parts are not "does it play" but the sequencing around it: which request goes
 * out when, what happens to the previous transcode, and whether the stop report survives the
 * screen closing. All of that lives here and none of it needs a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()
    private val resolver = mockk<PlaybackInfoResolver>()
    private val mediaSourceFactory = mockk<ExoMediaSourceFactory>()
    private val reporter = mockk<PlaybackReporter>(relaxed = true)
    private val playerHandle = FakePlayerHandle()

    private val source =
        PlayerFixtures.remoteSource(
            playMethod = PlayMethod.DIRECT_PLAY,
            startPositionTicks = RESUME_TICKS,
            audioTracks =
                listOf(
                    PlaybackTrack(index = 1, label = "English", language = "eng", codec = "ac3"),
                    PlaybackTrack(index = 2, label = "French", language = "fra", codec = "aac"),
                ),
            selectedAudioIndex = 1,
        )

    private val spec = PlaybackMediaItemSpec(mediaId = PlayerFixtures.ITEM_ID.toString(), uri = "https://server/x")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getItem(any()) } returns
            AppResult.Success(JellyfinItem(id = "x", name = "Arrival", type = ItemType.MOVIE))
        coEvery { resolver.resolve(any()) } returns AppResult.Success(source)
        every { mediaSourceFactory.create(any()) } returns spec
        every { reporter.startReporting(any(), any(), any()) } returns Job()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- opening ------------------------------------------------------------------------------

    @Test
    fun `resolves the route's item at its resume position and prepares the player`() =
        runTest(dispatcher) {
            val request = slot<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(request)) } returns AppResult.Success(source)

            viewModel()
            advanceUntilIdle()

            request.captured.itemId shouldBe PlayerFixtures.ITEM_ID
            request.captured.mediaSourceId shouldBe MEDIA_SOURCE_ID
            request.captured.startPositionTicks shouldBe RESUME_TICKS
            playerHandle.prepared.single().startPositionMs shouldBe RESUME_TICKS / 10_000L
            playerHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `publishes the play method and the tracks the server negotiated`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.playMethod shouldBe PlayMethod.DIRECT_PLAY
            state.audioTracks.map { it.index } shouldContainExactly listOf(1, 2)
            state.selectedAudioIndex shouldBe 1
            state.durationMs shouldBe PlayerFixtures.RUN_TIME_TICKS / 10_000L
            state.errorMessage.shouldBeNull()
        }

    @Test
    fun `reports the start once the stream is open`() =
        runTest(dispatcher) {
            viewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { reporter.reportStart(source, any()) }
            every { reporter.startReporting(any(), any(), any()) } returns Job()
        }

    @Test
    fun `surfaces a resolve failure instead of a blank screen`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.errorMessage!! shouldContain "server"
            model.uiState.value.isLoading shouldBe false
        }

    @Test
    fun `surfaces a source it cannot build a stream URL for`() =
        runTest(dispatcher) {
            every { mediaSourceFactory.create(any()) } returns null

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.errorMessage
                .shouldNotBeNull()
            playerHandle.prepared.size shouldBe 0
        }

    // ---- player events ------------------------------------------------------------------------

    @Test
    fun `clears the loading state when the player is ready`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ready)
            advanceUntilIdle()

            model.uiState.value.isBuffering shouldBe false
            model.uiState.value.isLoading shouldBe false
        }

    @Test
    fun `reports the stop when the item plays to its end`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe true
            coVerify(exactly = 1) { reporter.reportStop(source, match { it.hasEnded }) }
        }

    @Test
    fun `does not report the stop twice when the screen closes after the item ended`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.releaseSession()

            coVerify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    // ---- decoder fallback ---------------------------------------------------------------------

    @Test
    fun `a decoder failure re-resolves with direct play and direct stream forbidden`() =
        runTest(dispatcher) {
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 60_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(source.copy(playMethod = PlayMethod.TRANSCODE))

            playerHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_DECODING_FAILED, "boom"))
            advanceUntilIdle()

            val retry = requests.last()
            retry.enableDirectPlay shouldBe false
            retry.enableDirectStream shouldBe false
            retry.startPositionTicks shouldBe 600_000_000L
            model.uiState.value.userMessage shouldBe PlayerMessage.SwitchedToTranscode
            model.uiState.value.playMethod shouldBe PlayMethod.TRANSCODE
        }

    @Test
    fun `every re-resolve stops the outgoing transcode first`() =
        runTest(dispatcher) {
            val transcoding = source.copy(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = 20_000_000)
            coEvery { resolver.resolve(any()) } returns AppResult.Success(transcoding)
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // Skipping this is what strands an ffmpeg process on the server per quality change.
            coVerify(exactly = 1) { reporter.stopTranscoding(transcoding) }
        }

    @Test
    fun `gives up visibly once the fallback ladder is exhausted`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_DECODING_FAILED, "boom"))
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_DECODING_FAILED, "boom again"))
            advanceUntilIdle()

            model.uiState.value.errorMessage shouldBe "boom again"
            model.uiState.value.userMessage shouldBe PlayerMessage.PlaybackFailed
        }

    // ---- quality and tracks ---------------------------------------------------------------------

    @Test
    fun `choosing a quality re-resolves with that bitrate cap`() =
        runTest(dispatcher) {
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(source.copy(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = 3_000_000))

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // A cap below the file's bitrate is what forces the server to transcode.
            requests.last().maxStreamingBitrate shouldBe 3_000_000
            requests.last().startPositionTicks shouldBe 300_000_000L
            model.uiState.value.playMethod shouldBe PlayMethod.TRANSCODE
        }

    @Test
    fun `choosing the quality already in force does nothing`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.AUTO)
            advanceUntilIdle()

            coVerify(exactly = 1) { resolver.resolve(any()) }
        }

    @Test
    fun `an audio switch the stream already contains happens without a reload`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            val model = viewModel()
            advanceUntilIdle()

            model.selectAudioTrack(2)
            advanceUntilIdle()

            playerHandle.selectedAudioIndices shouldContainExactly listOf(2)
            model.uiState.value.selectedAudioIndex shouldBe 2
            coVerify(exactly = 1) { resolver.resolve(any()) }
        }

    @Test
    fun `an audio switch the stream cannot satisfy is re-requested from the server`() =
        runTest(dispatcher) {
            // The transcoding case: the server only ever sent the one audio track it was asked for.
            playerHandle.trackSelectionSucceeds = false
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.selectAudioTrack(2)
            advanceUntilIdle()

            requests.last().audioStreamIndex shouldBe 2
            model.uiState.value.userMessage shouldBe PlayerMessage.RestartedForTrackChange
        }

    @Test
    fun `turning subtitles off that the server has burned in re-requests the stream with minus one`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.selectSubtitleTrack(null)
            advanceUntilIdle()

            // null would make the server re-select the item's default subtitle.
            requests.last().subtitleStreamIndex shouldBe -1
        }

    // ---- teardown -----------------------------------------------------------------------------

    @Test
    fun `hands the final stop report to the scope that outlives the screen`() =
        runTest(dispatcher) {
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 45_000L)
            val model = viewModel()
            advanceUntilIdle()

            model.releaseSession()

            // viewModelScope is already cancelled here, so a report launched on it would vanish.
            coVerify(exactly = 1) { reporter.reportStopDetached(source, any()) }
            playerHandle.stopped shouldBe true
        }

    @Test
    fun `reports nothing when the screen closes before anything resolved`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())
            val model = viewModel()
            advanceUntilIdle()

            model.releaseSession()

            coVerify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    private fun viewModel() =
        PlayerViewModel(
            repository = repository,
            resolver = resolver,
            mediaSourceFactory = mediaSourceFactory,
            playerHandle = playerHandle,
            reporter = reporter,
            fallback = DecoderFallbackHandler(),
            savedStateHandle =
                SavedStateHandle(
                    mapOf(
                        PlayerViewModel.ARG_ITEM_ID to PlayerFixtures.ITEM_ID.toString(),
                        PlayerViewModel.ARG_MEDIA_SOURCE_ID to MEDIA_SOURCE_ID,
                        PlayerViewModel.ARG_START_TICKS to RESUME_TICKS,
                    ),
                ),
        )

    private companion object {
        const val MEDIA_SOURCE_ID = "source-1"
        const val RESUME_TICKS = 12_000_000_000L
    }
}
