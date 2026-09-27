package dev.jellyboost.player.ui

import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A cast session that ends with the receiver already gone — a Disconnect from the Cast notification —
 * leaves a final snapshot that is invalid. Its position is zero and belongs to nothing, and on a device
 * it wiped the film's resume position: the film came home at 0:00 and the server was sent a stop with
 * no position, which it reads as "played to the end, resume at zero". Split from
 * [PlayerViewModelCastTest] (detekt's `LargeClass`); the reattached twin is in
 * [PlayerViewModelCastReattachTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastEndTest : PlayerViewModelCastFixture() {
    /** Casting, the screen having read the television at [ON_THE_TELEVISION], which has since gone. */
    private suspend fun TestScope.castingThenReceiverGone(): PlayerViewModel {
        val model = castViewModel()
        advanceUntilIdle()
        framework.onSessionStarted("Living Room TV")
        advanceUntilIdle()
        castHandle.snapshot = ON_THE_TELEVISION
        model.onTick(ON_THE_TELEVISION)
        // Disconnected from the Cast notification: the final snapshot belongs to nothing.
        castHandle.snapshot = NOT_OURS
        local.resetCalls()
        clearMocks(reporter, answers = false)
        return model
    }

    @Test
    fun `a disconnect with the receiver already gone brings the film home at the last valid reading`() =
        runTest(dispatcher) {
            castingThenReceiverGone()
            val requests = echoResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            requests.single().castTarget shouldBe false
            requests.single().startPositionTicks shouldBe ON_THE_TELEVISION.positionTicks
            local.prepared.single().startPositionMs shouldBe ON_THE_TELEVISION.positionMs
        }

    @Test
    fun `a disconnect with the receiver already gone reports no position of zero, and none it cannot vouch for`() =
        runTest(dispatcher) {
            castingThenReceiverGone()
            echoResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            // A positionless stop is "played to the end, resume at zero" to the server: this wiped the
            // resume position on a device.
            coVerify(exactly = 1) { reporter.reportStop(source, ON_THE_TELEVISION) }
            coVerify(exactly = 0) { reporter.reportStop(any(), match { !it.isValid || it.positionMs == 0L }) }
            coVerify(exactly = 1) {
                reporter.reportStart(match { it.startPositionTicks == ON_THE_TELEVISION.positionTicks }, any())
            }
            coVerify(exactly = 0) { reporter.reportStart(match { it.startPositionTicks == 0L }, any()) }
            verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    @Test
    fun `a session that never read a valid position comes home at its start, and its stop carries none`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.snapshot = NOT_OURS
            val requests = recordResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            requests.last().startPositionTicks shouldBe source.startPositionTicks
            // Invalid, so the reporter closes the session without writing the user's data.
            coVerify(exactly = 1) { reporter.reportStop(source, NOT_OURS) }
        }

    @Test
    fun `a re-negotiation while the receiver's reading is invalid resumes from the last valid position`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            model.onTick(ON_THE_TELEVISION)
            castHandle.snapshot = NOT_OURS
            val requests = recordResolves()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // The invalid reading is at zero, and restarted the film there.
            requests.last().startPositionTicks shouldBe ON_THE_TELEVISION.positionTicks
        }

    private companion object {
        /** Fifteen minutes in: where the television got to before it was disconnected. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)

        /** A receiver not (yet) holding this item: every field zero, and flagged as belonging to nothing. */
        val NOT_OURS = PlaybackSnapshot(isValid = false)
    }
}
