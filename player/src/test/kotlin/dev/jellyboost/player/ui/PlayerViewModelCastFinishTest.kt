package dev.jellyboost.player.ui

import androidx.lifecycle.viewModelScope
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.cast.CastSessionCoordinator
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.upnext.UpNextEpisode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A film the receiver plays **to its end** is an end, not a loss. media3-cast never reports
 * `STATE_ENDED`; `CastPlayerHandle` reads the receiver's own `IDLE` / `FINISHED` status instead, and
 * what it then says is what the fake says here: an ended reading from then on, [PlayerEvent.Ended] once
 * — after [PlayerEvent.RemoteItemMissingCleared] when the item's disappearance was noticed a moment
 * before the finish status (`RemoteItemPresence.onFinished`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastFinishTest : PlayerViewModelCastFixture() {
    private suspend fun TestScope.castingAt(reading: PlaybackSnapshot): PlayerViewModel {
        val model = castViewModel()
        advanceUntilIdle()
        framework.onSessionStarted("Living Room TV")
        advanceUntilIdle()
        castHandle.playWhenReady = true
        castHandle.snapshot = reading
        castHandle.emit(PlayerEvent.Ready)
        castHandle.emit(PlayerEvent.IsPlayingChanged(true))
        advanceUntilIdle()
        model.onTick(reading)
        clearMocks(reporter, answers = false)
        return model
    }

    /** What the handle says as the receiver reports `IDLE` / `FINISHED` for the film it held. */
    private suspend fun TestScope.receiverFinishes(noticedGoneFirst: Boolean = false) {
        if (noticedGoneFirst) {
            castHandle.snapshot = PlaybackSnapshot(isValid = false)
            castHandle.emit(PlayerEvent.RemoteItemMissing(NEAR_THE_END))
            runCurrent()
        }
        castHandle.snapshot = FINISHED
        if (noticedGoneFirst) castHandle.emit(PlayerEvent.RemoteItemMissingCleared)
        castHandle.emit(PlayerEvent.Ended)
        runCurrent()
    }

    private fun TestScope.passGrace() {
        advanceTimeBy(CastSessionCoordinator.ITEM_LOST_GRACE.inWholeMilliseconds + 1L)
        runCurrent()
    }

    // ---- a screen attached --------------------------------------------------------------------------

    @Test
    fun `a film that finishes on the receiver ends the screen as played, and is never called stopped`() =
        runTest(dispatcher) {
            val model = castingAt(NEAR_THE_END)

            receiverFinishes(noticedGoneFirst = true)
            passGrace()
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe true
            model.uiState.value.userMessage shouldNotBe PlayerMessage.CastPlaybackStopped
            // One stop, as ended — the server marks it played — and never a positioned one.
            verify(exactly = 1) { reporter.reportStopDetached(source, match { it.hasEnded && it.isValid }) }
            verify(exactly = 1) { reporter.reportStopDetached(any(), any()) }
            coVerify(exactly = 0) { reporter.reportStop(any(), any()) }
        }

    @Test
    fun `an episode that finishes on the receiver advances to the next one there`() =
        runTest(dispatcher) {
            coEvery { upNextResolver.resolve(any()) } returns NEXT_EPISODE
            val model = castingAt(NEAR_THE_END)
            val next = source.copy(itemId = NEXT_ITEM, playSessionId = "next-session")
            coEvery { resolver.resolve(any()) } returns AppResult.Success(next)

            receiverFinishes()
            advanceUntilIdle()
            passGrace()

            model.uiState.value.hasEnded shouldBe false
            castHandle.preparedSource shouldBe next
            castHandle.prepared.last().playWhenReady shouldBe true
            model.uiState.value.userMessage shouldNotBe PlayerMessage.CastPlaybackStopped
            verify(exactly = 1) { reporter.reportStopDetached(source, match { it.hasEnded }) }
        }

    // ---- no screen ----------------------------------------------------------------------------------

    @Test
    fun `a detached film that finishes is closed once, as ended, and the casting bar lets it go`() =
        runTest(dispatcher) {
            val model = castingAt(NEAR_THE_END)
            // The screen goes as `onCleared` takes it: released, and its scope with it.
            model.releaseSession()
            model.viewModelScope.cancel()
            advanceUntilIdle()
            clearMocks(reporter, answers = false)

            receiverFinishes(noticedGoneFirst = true)
            passGrace()

            // Closed by the finish itself, not left for the session's end to find.
            verify(exactly = 1) { reporter.reportStopDetached(source, FINISHED) }
            coordinator.detached.value.shouldBeNull()

            framework.onSessionEnded()
            advanceUntilIdle()

            verify(exactly = 1) { reporter.reportStopDetached(any(), any()) }
        }

    private companion object {
        val NEAR_THE_END = PlaybackSnapshot(positionMs = 7_190_000L, durationMs = 7_200_000L, isPlaying = true)

        /** `RemoteItemPresence`'s ended reading: at the duration, where the server marks it played. */
        val FINISHED = NEAR_THE_END.copy(positionMs = 7_200_000L, isPlaying = false, hasEnded = true)

        val NEXT_ITEM: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c9")

        val NEXT_EPISODE =
            UpNextEpisode(
                itemId = NEXT_ITEM.toString(),
                title = "The One After",
                indexNumber = 4,
                parentIndexNumber = 1,
                imageUrl = "https://server/still",
            )
    }
}
