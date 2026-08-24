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

/**
 * That a new session is a *clean* session.
 *
 * The eight facts a playback session remembers live in one value reassigned in one place
 * (`PlayerViewModel.publish`), avoiding the classic temporal coupling of eight independent
 * fields kept in step by hand: nothing about `stopReported` alone would say it belonged to the
 * source that had been reported, and a field left un-reset would carry the previous film's answer
 * into the next one. These tests pin the properties that assignment is *for*, so the boxing
 * cannot be quietly unpicked back into per-field writes.
 *
 * Deliberately behavioural, not structural: none of this reaches inside the ViewModel. Each test
 * names a fact that would leak from one session into the next if the reset were partial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerActiveSessionTest : PlayerViewModelFixture() {
    private val nextItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c9")

    @Test
    fun `the next item does not inherit the last one's intro`() =
        runTest(dispatcher) {
            // The group is on an episode with an intro from 30s to 2min.
            coEvery { segmentLoader.load(any()) } returns listOf(intro)
            val model = viewModel()
            advanceUntilIdle()
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))
            model.uiState.value.skippableSegment shouldBe intro

            // The queue advances to something the segments API knows nothing about.
            coEvery { segmentLoader.load(any()) } returns emptyList()
            model.loadItem(nextItemId, 0L)
            advanceUntilIdle()

            // A skip offered 60 seconds into a film that has no intro is the previous session's
            // ranges outliving it — the leak the boxed reset makes impossible.
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))
            model.uiState.value.skippableSegment
                .shouldBeNull()
        }

    @Test
    fun `an item that ended does not spend the next item's stop report`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            // The episode plays out: its stop is reported, and the guard against reporting it twice
            // is now armed.
            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()
            coVerify(exactly = 1) { reporter.reportStopDetached(any(), any()) }

            // The group moves on, and the screen is left mid-way through the next item.
            val next = source.copy(itemId = nextItemId)
            coEvery { resolver.resolve(any()) } returns AppResult.Success(next)
            model.loadItem(nextItemId, 0L)
            advanceUntilIdle()
            model.releaseSession()
            advanceUntilIdle()

            // A guard left standing from the item before would swallow this one silently: the server
            // would keep a session open on an item nobody is watching, and the resume position would
            // never be written.
            coVerify(exactly = 1) { reporter.reportStopDetached(next, any()) }
        }

    @Test
    fun `the audio the next open resolved is applied, not the one the last open was waiting on`() =
        runTest(dispatcher) {
            // The first open resolved track 1 and the player has not reported its tracks yet, so the
            // selection is still pending when the queue moves on.
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

            // The pending choice belongs to the open that resolved it. Carried over, it would select
            // the previous item's language on the new stream the moment its tracks arrived.
            playerHandle.selectedAudioIndices shouldBe listOf(2)
        }

    @Test
    fun `every control is inert until there is a session for it to act on`() =
        runTest(dispatcher) {
            // The other end of the same invariant: eight methods below now open with "is there a
            // session", and a resolve that never produced one must leave every one of them a no-op
            // rather than half-acting on a stream that does not exist.
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

            // Nothing was re-negotiated on the strength of a session that never opened…
            coVerify(exactly = 1) { resolver.resolve(any()) }
            // …no stop was reported for a source that was never started…
            coVerify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
            // …and the quality picker did not publish a cap nothing is playing at.
            model.uiState.value.quality shouldBe PlaybackQuality.AUTO
        }

    @Test
    fun `the position a group hands over survives the whole reset`() =
        runTest(dispatcher) {
            // The plainest statement that the new session is built from the new open's answer rather
            // than patched onto the old one's: nothing about the previous item reaches the request.
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
