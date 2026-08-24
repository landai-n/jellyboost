package dev.jellyboost.player.ui

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

// A playback session's facts live in one value reassigned in one place (`PlayerViewModel.publish`)
// rather than as independent fields kept in step by hand. These tests are deliberately behavioural
// (none reach inside the ViewModel): each pins a fact that would leak between sessions if that
// single reassignment were ever unpicked back into partial, per-field writes.
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerActiveSessionTest : PlayerViewModelFixture() {
    private val nextItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c9")

    @Test
    fun `the next item does not inherit the last one's intro`() =
        runTest(dispatcher) {
            coEvery { segmentLoader.load(any()) } returns listOf(intro)
            val model = viewModel()
            advanceUntilIdle()
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))
            model.uiState.value.skippableSegment shouldBe intro

            coEvery { segmentLoader.load(any()) } returns emptyList()
            model.loadItem(nextItemId, 0L)
            advanceUntilIdle()

            // A skip offered here would be the previous session's ranges outliving it.
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))
            model.uiState.value.skippableSegment
                .shouldBeNull()
        }

    @Test
    fun `an item that ended does not spend the next item's stop report`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()
            coVerify(exactly = 1) { reporter.reportStopDetached(any(), any()) }

            val next = source.copy(itemId = nextItemId)
            coEvery { resolver.resolve(any()) } returns AppResult.Success(next)
            model.loadItem(nextItemId, 0L)
            advanceUntilIdle()
            model.releaseSession()
            advanceUntilIdle()

            // A guard left standing from the item before would swallow this one silently: the
            // server would keep a session open, and the resume position would never be written.
            coVerify(exactly = 1) { reporter.reportStopDetached(next, any()) }
        }

    @Test
    fun `the audio the next open resolved is applied, not the one the last open was waiting on`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.trackSelectionSucceeds = true
            coEvery { resolver.resolve(any()) } returns
                AppResult.Success(source.copy(itemId = nextItemId, selectedAudioIndex = 2))
            model.loadItem(nextItemId, 0L)
            advanceUntilIdle()
            playerHandle.selectedAudioIndices.clear()

            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            // Carried over, the pending choice would select the previous item's language the
            // moment tracks arrived on the new stream.
            playerHandle.selectedAudioIndices shouldBe listOf(2)
        }

    @Test
    fun `every control is inert until there is a session for it to act on`() =
        runTest(dispatcher) {
            // Each method below opens with "is there a session"; a resolve that never produced one
            // must leave every one a no-op rather than half-acting on a stream that doesn't exist.
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())
            val model = viewModel()
            advanceUntilIdle()

            model.selectAudioTrack(2)
            model.selectSubtitleTrack(3)
            model.selectQuality(PlaybackQuality.LOW)
            model.skipCurrentSegment()
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))
            model.releaseSession()
            advanceUntilIdle()

            coVerify(exactly = 1) { resolver.resolve(any()) }
            coVerify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
            model.uiState.value.quality shouldBe PlaybackQuality.AUTO
        }

    @Test
    fun `the position a group hands over survives the whole reset`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.loadItem(nextItemId, 90_000L.millisToTicks())
            advanceUntilIdle()

            requests.last().itemId shouldBe nextItemId
            requests.last().startPositionTicks shouldBe 90_000L.millisToTicks()
        }
}
