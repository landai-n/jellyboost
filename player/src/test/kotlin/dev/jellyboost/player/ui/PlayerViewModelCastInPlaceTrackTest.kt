package dev.jellyboost.player.ui

import dev.jellyboost.player.model.PlaybackSnapshot
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A track chosen **in place** on a receiver (subtitles off, a side-loaded subtitle) replaces the
 * session's source with a copy (`withSelectedSubtitle`) while the receiver's load is unchanged. The
 * coordinator must still recognise the copy as the load the receiver holds: same item, same media
 * source, same play session. Otherwise every detached reading is refused, the bar freezes, a reopen
 * reloads and rewinds, and the stop carries a stale position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastInPlaceTrackTest : PlayerViewModelCastFixture() {
    /** Casting at [ON_THE_TELEVISION], subtitles switched off in place, then the screen leaves. */
    private suspend fun TestScope.subtitleChangedThenLeft() {
        val model = castViewModel()
        advanceUntilIdle()
        framework.onSessionStarted("Living Room TV")
        advanceUntilIdle()
        castHandle.snapshot = ON_THE_TELEVISION
        castHandle.playWhenReady = true
        model.onTick(ON_THE_TELEVISION)

        model.selectSubtitleTrack(null)
        // In place: nothing was reloaded on the receiver.
        castHandle.prepared.size shouldBe 1

        model.releaseSession()
        advanceUntilIdle()
        clearMocks(reporter, answers = false)
    }

    @Test
    fun `after an in-place subtitle change the bar and the ticker keep following the television`() =
        runTest(dispatcher) {
            subtitleChangedThenLeft()
            castHandle.snapshot = LATER

            coordinator.readReceiver() shouldBe LATER
            coordinator.lastHeld shouldBe LATER
        }

    @Test
    fun `after an in-place subtitle change a reopen adopts the television's film without reloading it`() =
        runTest(dispatcher) {
            subtitleChangedThenLeft()
            castHandle.snapshot = LATER
            val requests = recordResolves()

            val reopened = castViewModel()
            advanceUntilIdle()

            requests.shouldBeEmpty()
            castHandle.prepared.size shouldBe 1
            reopened.position.value.positionMs shouldBe LATER.positionMs
            verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    @Test
    fun `after an in-place subtitle change the detached session is closed at the live position`() =
        runTest(dispatcher) {
            subtitleChangedThenLeft()
            castHandle.snapshot = LATER
            coordinator.readReceiver()
            castHandle.snapshot = PlaybackSnapshot(isValid = false)

            framework.onSessionEnded()
            advanceUntilIdle()

            verify(exactly = 1) { reporter.reportStopDetached(any(), LATER) }
        }

    /**
     * The audio twin. `CastPlayerHandle.selectAudioTrack` always answers `false` today, so this cannot
     * happen on a real receiver yet; the fake answers `true` so the same shape stays covered the day it can.
     */
    @Test
    fun `after an in-place audio change the bar and the ticker keep following the television`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.snapshot = ON_THE_TELEVISION
            castHandle.playWhenReady = true
            model.onTick(ON_THE_TELEVISION)
            model.selectAudioTrack(2)
            castHandle.prepared.size shouldBe 1
            model.releaseSession()
            advanceUntilIdle()
            castHandle.snapshot = LATER

            coordinator.readReceiver() shouldBe LATER
        }

    private companion object {
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, durationMs = 7_200_000L, isPlaying = true)
        val LATER = ON_THE_TELEVISION.copy(positionMs = 1_500_000L)
    }
}
