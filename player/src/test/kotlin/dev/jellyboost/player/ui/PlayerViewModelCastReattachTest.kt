package dev.jellyboost.player.ui

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Opening the player for the film a receiver is already playing: it must reattach — no
 * `PlaybackInfo`, no `prepare`, no start or stop report — and anything else must close the session
 * it replaces exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastReattachTest : PlayerViewModelCastFixture() {
    // ---- coming back to the film the television is playing ----------------------------------------

    /**
     * A film cast from a screen that has since gone: the receiver plays on at [ON_THE_TELEVISION] and
     * the coordinator holds the source. Every call recorded until now is forgotten, so what follows is
     * only what the *next* screen does.
     */
    private suspend fun TestScope.leftPlayingOnTheTelevision() {
        val first = castViewModel()
        advanceUntilIdle()
        framework.onSessionStarted("Living Room TV")
        advanceUntilIdle()
        castHandle.snapshot = ON_THE_TELEVISION.copy(durationMs = TWO_HOURS_MS)
        castHandle.playWhenReady = true
        first.releaseSession()
        advanceUntilIdle()
        castHandle.resetCalls()
        castHandle.playWhenReady = true
        clearMocks(reporter, answers = false)
    }

    @Test
    fun `reopening the film the television is playing reattaches — nothing negotiated, loaded or reported`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val requests = recordResolves()

            castViewModel()
            advanceUntilIdle()

            // A new PlaybackInfo stopped the receiver, rebuffered it and started a second transcode.
            requests.shouldBeEmpty()
            castHandle.prepared.shouldBeEmpty()
            castHandle.hadNoTransportCalls shouldBe true
            coVerify(exactly = 0) { reporter.reportStart(any(), any()) }
            coVerify(exactly = 0) { reporter.reportStop(any(), any()) }
            verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    @Test
    fun `the reattached screen takes the reports over, under the session the server already knows`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val reported = slot<() -> PlaybackMediaSource?>()

            castViewModel()
            advanceUntilIdle()

            // One ticker — the screen's; the coordinator's was stopped by the attach.
            verify(exactly = 1) { reporter.startReporting(any(), capture(reported), any()) }
            reported.captured() shouldBe source
        }

    @Test
    fun `the reattached screen shows where the television is, and that it is playing`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()

            val model = castViewModel()
            advanceUntilIdle()

            model.position.value.positionMs shouldBe ON_THE_TELEVISION.positionMs
            model.uiState.value.isLoading shouldBe false
            model.uiState.value.isPlaying shouldBe true
            model.uiState.value.durationMs shouldBe TWO_HOURS_MS
            model.uiState.value.cast.isCasting shouldBe true
        }

    @Test
    fun `a reattached pause pauses the television rather than reloading it`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val model = castViewModel()
            advanceUntilIdle()

            model.togglePlayPause()

            castHandle.pauseCount shouldBe 1
            castHandle.prepared.shouldBeEmpty()
        }

    @Test
    fun `a receiver still buffering the film is reattached to, and shown buffering`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            castHandle.snapshot = NOT_OURS
            castHandle.emit(PlayerEvent.Buffering(true))
            advanceUntilIdle()
            val requests = recordResolves()

            val model = castViewModel()
            advanceUntilIdle()

            requests.shouldBeEmpty()
            model.uiState.value.showsBufferingRing shouldBe true
            // The invalid reading carried no position: the last valid one the coordinator held stands in
            // for it — not the source's own start, which is where the film was first sent.
            model.position.value.positionMs shouldBe ON_THE_TELEVISION.positionMs
        }

    // ---- the session ending under a reattached screen ------------------------------------------------

    /** A reattached screen, and nothing recorded before the session ends. */
    private suspend fun TestScope.reattached(): PlayerViewModel {
        val model = castViewModel()
        advanceUntilIdle()
        local.resetCalls()
        clearMocks(reporter, answers = false)
        return model
    }

    /** No report may carry zero, or a position nobody read: either is what wiped the resume position. */
    private fun noReportCarriesZero() {
        coVerify(exactly = 0) { reporter.reportStop(any(), match { !it.isValid || it.positionMs == 0L }) }
        coVerify(exactly = 0) { reporter.reportStart(match { it.startPositionTicks == 0L }, any()) }
        verify(exactly = 0) { reporter.reportStopDetached(any(), match { !it.isValid || it.positionMs == 0L }) }
    }

    @Test
    fun `a reattached film whose session ends with the receiver gone comes home where the screen last read it`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val model = reattached()
            // The television plays on, and the screen follows it.
            model.onTick(AT_27_20)
            castHandle.snapshot = NOT_OURS
            val requests = echoResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            requests.single().castTarget shouldBe false
            requests.single().startPositionTicks shouldBe AT_27_20.positionTicks
            local.prepared.single().startPositionMs shouldBe AT_27_20.positionMs
            coVerify(exactly = 1) { reporter.reportStop(source, AT_27_20) }
            coVerify(exactly = 1) {
                reporter.reportStart(match { it.startPositionTicks == AT_27_20.positionTicks }, any())
            }
            noReportCarriesZero()
        }

    @Test
    fun `a reattached film ending before the screen reads it comes home where the coordinator last saw it`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            // The casting bar read the television at 27:20, then it went back to buffering.
            castHandle.snapshot = AT_27_20
            coordinator.readReceiver()
            castHandle.snapshot = NOT_OURS
            castHandle.emit(PlayerEvent.Buffering(true))
            advanceUntilIdle()
            val model = reattached()
            val requests = echoResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            model.uiState.value.cast.isCasting shouldBe false
            // The source's start, which the old code fell back to, is where the film was first sent.
            requests.single().startPositionTicks shouldBe AT_27_20.positionTicks
            coVerify(exactly = 1) { reporter.reportStop(source, AT_27_20) }
            noReportCarriesZero()
        }

    /**
     * The device walk, step for step: reattach, follow the television to 663 s, then the Cast
     * notification's X. media3's `RemoteCastPlayer` drops its client but keeps its timeline, so the
     * cast handle still claims our item — at zero — when the coordinator reads it (before routing moves),
     * and once routing is local the idle local player answers a valid zero too. Neither may bring the
     * film home at 0:00, reach a stop, or reach a progress tick.
     */
    @Test
    fun `the device walk - a torn-down receiver and the idle local player both reading zero change nothing`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val tickerReads = mutableListOf<() -> PlaybackSnapshot>()
            every { reporter.startReporting(any(), any(), capture(tickerReads)) } returns Job()
            val model = reattached()
            model.onTick(AT_663)
            castHandle.snapshot = TORN_DOWN
            local.snapshot = PlaybackSnapshot()
            val requests = echoResolves()

            framework.onSessionEnded()
            // The screen's own ticker fires between the routing switch and the home open.
            val lateTick = tickerReads.last().invoke()
            advanceUntilIdle()

            lateTick.isValid shouldBe false
            requests.single().startPositionTicks shouldBe AT_663.positionTicks
            local.prepared.single().startPositionMs shouldBe AT_663.positionMs
            coVerify(exactly = 1) { reporter.reportStop(source, AT_663) }
            noReportCarriesZero()
        }

    @Test
    fun `a zero read off a torn-down receiver before the end is not remembered as where the film is`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val model = reattached()
            model.onTick(AT_663)
            model.onTick(TORN_DOWN)
            castHandle.snapshot = NOT_OURS
            val requests = echoResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            requests.single().startPositionTicks shouldBe AT_663.positionTicks
            noReportCarriesZero()
        }

    @Test
    fun `a reattached screen that goes as the receiver stops answering hands its last reading to the coordinator`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val model = reattached()
            model.onTick(AT_27_20)
            castHandle.snapshot = NOT_OURS

            model.releaseSession()
            advanceUntilIdle()
            framework.onSessionEnded()
            advanceUntilIdle()

            verify(exactly = 1) { reporter.reportStopDetached(source, AT_27_20) }
            noReportCarriesZero()
        }

    @Test
    fun `leaving the reattached screen and ending the session still reports the film exactly once`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val model = castViewModel()
            advanceUntilIdle()

            model.releaseSession()
            advanceUntilIdle()
            framework.onSessionEnded()
            advanceUntilIdle()

            verify(exactly = 1) { reporter.reportStopDetached(source, any()) }
            coVerify(exactly = 0) { reporter.reportStop(any(), any()) }
        }

    @Test
    fun `opening another film replaces it on the television and closes the old one's session once`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val other = source.copy(itemId = OTHER_ITEM, playSessionId = "other-session")
            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(other)

            castViewModel(navArgs(PlayerViewModel.ARG_ITEM_ID to OTHER_ITEM.toString()))
            advanceUntilIdle()

            requests.single().itemId shouldBe OTHER_ITEM
            castHandle.prepared.size shouldBe 1
            // The orphan's stop, where the television had it — and not a second one at session end.
            verify(
                exactly = 1,
            ) { reporter.reportStopDetached(source, ON_THE_TELEVISION.copy(durationMs = TWO_HOURS_MS)) }
            framework.onSessionEnded()
            advanceUntilIdle()
            verify(exactly = 1) { reporter.reportStopDetached(source, any()) }
        }

    /**
     * The window the review found: the new screen's open has already loaded the other film on the
     * receiver, but `publish` is still suspended in the start report, so `cast.attach()` has not run and
     * the coordinator still holds the old source. The casting bar's poll (and the detached ticker) read
     * the receiver there — and must not take the other film's position as the held one's.
     */
    @Test
    fun `another film loaded before the new screen attaches cannot lend its position to the one it replaces`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            val other = source.copy(itemId = OTHER_ITEM, playSessionId = "other-session")
            coEvery { resolver.resolve(any()) } returns AppResult.Success(other)
            val startReported = CompletableDeferred<Unit>()
            coEvery { reporter.reportStart(any(), any()) } coAnswers { startReported.await() }

            castViewModel(navArgs(PlayerViewModel.ARG_ITEM_ID to OTHER_ITEM.toString()))
            advanceUntilIdle()
            castHandle.prepared.size shouldBe 1
            castHandle.snapshot = OTHER_FILM_READING
            val barReading = coordinator.readReceiver()
            startReported.complete(Unit)
            advanceUntilIdle()

            barReading.isValid shouldBe false
            verify(
                exactly = 1,
            ) { reporter.reportStopDetached(source, ON_THE_TELEVISION.copy(durationMs = TWO_HOURS_MS)) }
            verify(exactly = 0) { reporter.reportStopDetached(source, OTHER_FILM_READING) }
        }

    @Test
    fun `a television that has let go of the film is not reattached to, and its old session is closed`() =
        runTest(dispatcher) {
            leftPlayingOnTheTelevision()
            // Stopped from the television's remote, the grace period not yet up: a reading of nothing.
            castHandle.snapshot = NOT_OURS
            // A new negotiation is a new play session on the server, even for the same item.
            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(source.copy(playSessionId = "session-2"))

            castViewModel()
            advanceUntilIdle()

            requests.single().castTarget shouldBe true
            castHandle.prepared.size shouldBe 1
            verify(
                exactly = 1,
            ) { reporter.reportStopDetached(source, ON_THE_TELEVISION.copy(durationMs = TWO_HOURS_MS)) }
        }

    private companion object {
        /** Fifteen minutes in: where the television got to before the screen went. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)

        /** About 27:20, and playing: where the television had got to when the session was disconnected. */
        val AT_27_20 = PlaybackSnapshot(positionMs = 1_640_000L, durationMs = 7_200_000L, isPlaying = true)

        /** Five minutes into the *other* film the new screen loaded. */
        val OTHER_FILM_READING = PlaybackSnapshot(positionMs = 300_000L, durationMs = 5_400_000L, isPlaying = true)

        /** 663 s, where the television was on the device walk when the session was disconnected. */
        val AT_663 = PlaybackSnapshot(positionMs = 663_000L, durationMs = 7_200_000L, isPlaying = true)

        /**
         * What a torn-down `RemoteCastPlayer` answers: its stale timeline still holds our item, so the
         * reading counts as valid, at zero.
         */
        val TORN_DOWN = PlaybackSnapshot(positionMs = 0L, isValid = true)

        /** A receiver not (yet) holding this item: every field zero, and flagged as belonging to nothing. */
        val NOT_OURS = PlaybackSnapshot(isValid = false)

        const val TWO_HOURS_MS = 7_200_000L

        val OTHER_ITEM: UUID = UUID.fromString("9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4")
    }
}
