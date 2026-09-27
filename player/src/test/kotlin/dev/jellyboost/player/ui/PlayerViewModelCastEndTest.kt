package dev.jellyboost.player.ui

import dev.jellyboost.player.cast.CastSessionCoordinator
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    // ---- a torn-down receiver, or the idle local player, answering a *valid* zero -------------------

    @Test
    fun `a final reading of zero off a torn-down receiver brings the film home at the last valid reading`() =
        runTest(dispatcher) {
            castingThenReceiverGone()
            // `RemoteCastPlayer` keeps its timeline after the session goes: our item, at zero, "valid".
            castHandle.snapshot = TORN_DOWN
            val requests = echoResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            requests.single().startPositionTicks shouldBe ON_THE_TELEVISION.positionTicks
            coVerify(exactly = 1) { reporter.reportStop(source, ON_THE_TELEVISION) }
            coVerify(exactly = 0) { reporter.reportStop(any(), match { it.positionMs == 0L }) }
        }

    @Test
    fun `a zero reading is dropped from the screen and the ticker unless the user sought to zero`() =
        runTest(dispatcher) {
            val model = castingThenReceiverGone()
            val tickerReads = mutableListOf<() -> PlaybackSnapshot>()
            every { reporter.startReporting(any(), any(), capture(tickerReads)) } returns Job()
            castHandle.snapshot = ON_THE_TELEVISION
            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()
            model.onTick(ON_THE_TELEVISION)
            castHandle.snapshot = TORN_DOWN

            model.onTick(TORN_DOWN)

            // Neither the scrubber nor a progress report may take the zero.
            model.position.value.positionMs shouldBe ON_THE_TELEVISION.positionMs
            tickerReads.last().invoke().isValid shouldBe false
        }

    @Test
    fun `a seek to the start is the user's, so zero is then where the film is`() =
        runTest(dispatcher) {
            val model = castingThenReceiverGone()
            castHandle.snapshot = ON_THE_TELEVISION
            model.seekTo(0L)
            model.onTick(castHandle.snapshot)
            castHandle.snapshot = NOT_OURS
            val requests = echoResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            requests.single().startPositionTicks shouldBe 0L
            coVerify(exactly = 1) { reporter.reportStop(source, match { it.isValid && it.positionMs == 0L }) }
        }

    @Test
    fun `a receiver that drops the item holding a stale zero is closed at the last valid reading`() =
        runTest(dispatcher) {
            castingThenReceiverGone()
            castHandle.emit(PlayerEvent.RemoteItemMissing(TORN_DOWN))
            runCurrent()
            advanceTimeBy(CastSessionCoordinator.ITEM_LOST_GRACE.inWholeMilliseconds + 1L)
            runCurrent()

            // Detached, not the cancellable `reportStop` path: the message is already on screen by
            // the time the user could possibly leave it.
            verify(exactly = 1) { reporter.reportStopDetached(source, ON_THE_TELEVISION) }
            verify(exactly = 0) { reporter.reportStopDetached(any(), match { it.positionMs == 0L }) }
            coVerify(exactly = 0) { reporter.reportStop(any(), any()) }
        }

    // ---- vetted's `foreign` term: a non-zero reading from the wrong player -------------------------

    /**
     * The idle local player answers a **valid, non-zero** reading the instant routing falls back to
     * it — whatever it happened to be sitting on before the transfer, unrelated to the television's
     * own position. `staleZero` never sees this: it only fires at position zero. Only `vetted`'s
     * `foreign` term (`ActiveSession.onReceiver != isCasting`) catches a reading from the player the
     * session was not opened on.
     */
    @Test
    fun `a non-zero reading from the idle local player right after the transfer is invalid`() =
        runTest(dispatcher) {
            val tickerReads = mutableListOf<() -> PlaybackSnapshot>()
            every { reporter.startReporting(any(), any(), capture(tickerReads)) } returns Job()
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.snapshot = AT_40_MIN
            model.onTick(AT_40_MIN)
            // Left over from whatever this device was doing before the transfer; the idle local
            // player never touches it again on its own.
            local.snapshot = STALE_LOCAL_READING
            val requests = echoResolves()

            framework.onSessionEnded()
            // Routing has already fallen back to the idle local player; the home open that would
            // replace this session — and start a fresh ticker for it — has not run yet.
            val lateTick = tickerReads.last().invoke()
            advanceUntilIdle()

            lateTick.isValid shouldBe false
            requests.single().startPositionTicks shouldBe AT_40_MIN.positionTicks
            coVerify(exactly = 0) {
                reporter.reportStop(any(), match { it.positionMs == STALE_LOCAL_READING.positionMs })
            }
            coVerify(exactly = 0) {
                reporter.reportStart(match { it.startPositionTicks == STALE_LOCAL_READING.positionTicks }, any())
            }
        }

    private companion object {
        /** What a torn-down `RemoteCastPlayer` answers: its stale timeline still holds our item, at zero. */
        val TORN_DOWN = PlaybackSnapshot(positionMs = 0L, isValid = true)

        /** Fifteen minutes in: where the television got to before it was disconnected. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)

        /** A receiver not (yet) holding this item: every field zero, and flagged as belonging to nothing. */
        val NOT_OURS = PlaybackSnapshot(isValid = false)

        /** Forty minutes in: where the television actually is when the session ends. */
        val AT_40_MIN = PlaybackSnapshot(positionMs = 2_400_000L, isPlaying = true)

        /**
         * Ten minutes in: whatever this device was doing before it was cast — not zero, so the
         * `staleZero` rule alone would wave it through unchallenged.
         */
        val STALE_LOCAL_READING = PlaybackSnapshot(positionMs = 600_000L, isPlaying = false)
    }
}
