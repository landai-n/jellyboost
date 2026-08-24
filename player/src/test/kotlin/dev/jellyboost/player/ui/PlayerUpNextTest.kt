package dev.jellyboost.player.ui

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.segments.MediaSegment
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.upnext.UpNextEpisode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What the player does about the episode that follows the one playing.
 *
 * The card itself is a composable and is tested with the rest of them; what is here is everything
 * underneath it — when the successor is fetched, whose session the answer is allowed to land on,
 * what a tap actually opens, and the two collisions the feature has with behaviour that was already
 * there: the outro skip button it supersedes, and the `Ended` event that pops the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerUpNextTest : PlayerViewModelFixture() {
    private val nextItemId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c9")

    private val nextEpisode =
        UpNextEpisode(
            itemId = nextItemId.toString(),
            title = "The One After",
            indexNumber = 4,
            parentIndexNumber = 1,
            imageUrl = "https://server/still",
        )

    /** An outro over the last hundred seconds of the two-hour fixture item. */
    private val outro = MediaSegment(MediaSegmentKind.OUTRO, startMs = OUTRO_START_MS, endMs = DURATION_MS)

    // ---- prefetch ---------------------------------------------------------------------------------

    @Test
    fun `the successor is fetched once the session is open, and offered when the outro starts`() =
        runTest(dispatcher) {
            coEvery { upNextResolver.resolve(any()) } returns nextEpisode
            coEvery { segmentLoader.load(any()) } returns listOf(outro)
            val model = viewModel()
            advanceUntilIdle()

            // Nothing while the episode is still playing…
            model.onTick(PlaybackSnapshot(positionMs = 600_000L))
            model.card.shouldBeNull()

            // …and the offer the moment the credits start.
            model.onTick(PlaybackSnapshot(positionMs = OUTRO_START_MS))
            model.card?.episode shouldBe nextEpisode
        }

    @Test
    fun `an item with no successor is never offered one`() =
        runTest(dispatcher) {
            // The fixture's default: a film, and `null` is the resolver's ordinary answer for one.
            val model = viewModel()
            advanceUntilIdle()

            model.onTick(PlaybackSnapshot(positionMs = DURATION_MS - 1_000L))

            model.card.shouldBeNull()
        }

    @Test
    fun `a slow lookup for the episode before does not land on the episode after`() =
        runTest(dispatcher) {
            // Episode N's lookup takes two server calls, and the session can be replaced while it is
            // in flight — by the card itself, or by a group taking over. Landed unguarded, it would
            // offer the episode that is already playing.
            coEvery { upNextResolver.resolve(PlayerFixtures.ITEM_ID.toString()) } coAnswers {
                delay(SLOW_LOOKUP_MS)
                nextEpisode
            }
            coEvery { upNextResolver.resolve(nextItemId.toString()) } returns null

            val model = viewModel()
            // Far enough for the open to publish, not far enough for the lookup to come back.
            advanceTimeBy(SLOW_LOOKUP_MS / 2)

            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))
            model.loadItem(nextItemId, 0L)
            advanceUntilIdle()

            model.onTick(PlaybackSnapshot(positionMs = DURATION_MS - 1_000L))

            model.card.shouldBeNull()
        }

    @Test
    fun `in a group nothing is fetched and nothing is offered`() =
        runTest(dispatcher) {
            // The server owns what everyone watches next (key decision 11); a member offering its own
            // successor would be offering something it must not act on.
            syncPlayState.value = inGroup()
            coEvery { upNextResolver.resolve(any()) } returns nextEpisode
            val model = viewModel()
            advanceUntilIdle()

            model.onTick(PlaybackSnapshot(positionMs = DURATION_MS - 1_000L))

            coVerify(exactly = 0) { upNextResolver.resolve(any()) }
            model.card.shouldBeNull()
        }

    // ---- the tap ----------------------------------------------------------------------------------

    @Test
    fun `taking the card opens the next episode playing, from its start`() =
        runTest(dispatcher) {
            val model = offering()
            val request = slot<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(request)) } returns AppResult.Success(source.copy(itemId = nextItemId))
            playerHandle.resetCalls()

            model.playNextEpisode()
            advanceUntilIdle()

            request.captured.itemId shouldBe nextItemId
            request.captured.startPositionTicks shouldBe 0L
            // Playing, unlike the group's own swap: a tap *is* the decision to watch it.
            playerHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `an Auto session carries its Auto-ness into the next episode`() =
        runTest(dispatcher) {
            val model = offering()
            val request = slot<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(request)) } returns AppResult.Success(source.copy(itemId = nextItemId))

            model.playNextEpisode()
            advanceUntilIdle()

            request.captured.autoBitrate shouldBe true
            request.captured.maxStreamingBitrate.shouldBeNull()
        }

    @Test
    fun `a hand-picked cap survives into the next episode`() =
        runTest(dispatcher) {
            // Someone who pinned this episode to Low is on a connection that needs it; the next one
            // must not quietly go back to Auto and re-measure the very link that made them choose.
            val capped =
                PlayerFixtures.remoteSource(
                    startPositionTicks = RESUME_TICKS,
                    maxStreamingBitrate = PlaybackQuality.LOW.maxStreamingBitrate,
                )
            coEvery { resolver.resolve(any()) } returns AppResult.Success(capped)
            val model = offering()
            model.uiState.value.quality shouldBe PlaybackQuality.LOW

            val request = slot<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(request)) } returns AppResult.Success(capped.copy(itemId = nextItemId))
            model.playNextEpisode()
            advanceUntilIdle()

            request.captured.maxStreamingBitrate shouldBe PlaybackQuality.LOW.maxStreamingBitrate
            request.captured.autoBitrate shouldBe false
        }

    @Test
    fun `the session's playback rate is re-applied to the next episode`() =
        runTest(dispatcher) {
            // The same guarantee a re-resolve has: speed belongs to the session, and the swap builds
            // a fresh media item that starts at 1x.
            val model = offering()
            model.selectSpeed(PlaybackSpeed.ONE_AND_HALF)
            advanceUntilIdle()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))

            model.playNextEpisode()
            advanceUntilIdle()

            playerHandle.playbackSpeeds shouldContainExactly listOf(1.5f, 1.5f)
        }

    @Test
    fun `the card goes away as soon as it is taken`() =
        runTest(dispatcher) {
            val model = offering()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))

            model.playNextEpisode()

            model.card.shouldBeNull()
        }

    @Test
    fun `a tap in a group does nothing at all`() =
        runTest(dispatcher) {
            val model = offering()
            syncPlayState.value = inGroup()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.playNextEpisode()
            advanceUntilIdle()

            playerHandle.prepared.shouldBeEmpty()
        }

    @Test
    fun `dismissing it keeps it away for the rest of the episode`() =
        runTest(dispatcher) {
            val model = offering()

            model.dismissUpNext()
            model.card.shouldBeNull()

            model.onTick(PlaybackSnapshot(positionMs = OUTRO_START_MS + 10_000L))

            model.card.shouldBeNull()
        }

    // ---- collisions -------------------------------------------------------------------------------

    @Test
    fun `the outro skip button gives way to the card`() =
        runTest(dispatcher) {
            // The card is a strict superset of that button — it skips the credits *and* starts the
            // next episode — so drawing both would put two competing affordances over the same
            // seconds of screen.
            val model = offering()

            model.onTick(PlaybackSnapshot(positionMs = OUTRO_START_MS + 1_000L))

            model.card?.episode shouldBe nextEpisode
            model.skipButton.shouldBeNull()
        }

    @Test
    fun `an intro offered inside the window keeps its button`() =
        runTest(dispatcher) {
            // The suppression is keyed on the segment's *kind*, not merely on the card being up: only
            // the outro button is redundant beside the card. This item has no outro at all, so the
            // card rides the runtime fallback window — and a series that tags a recap inside it still
            // gets its skip button.
            val lateIntro = MediaSegment(MediaSegmentKind.INTRO, startMs = FALLBACK_START_MS, endMs = DURATION_MS)
            coEvery { upNextResolver.resolve(any()) } returns nextEpisode
            coEvery { segmentLoader.load(any()) } returns listOf(lateIntro)
            val model = viewModel()
            advanceUntilIdle()

            // Twice: the card goes up on the first tick, and the second is the one that would be
            // suppressed if the kind were not checked.
            model.onTick(PlaybackSnapshot(positionMs = FALLBACK_START_MS + 1_000L))
            model.onTick(PlaybackSnapshot(positionMs = FALLBACK_START_MS + 2_000L))

            model.card?.episode shouldBe nextEpisode
            model.skipButton shouldBe lateIntro
        }

    @Test
    fun `a tap that races the end of the episode keeps the screen open`() =
        runTest(dispatcher) {
            // `hasEnded` is what `PlayerScreen` turns into a pop, and the last frames of an episode
            // are exactly when the card is on screen: without the guard the route would close over
            // the episode the user has just asked for.
            val model = offering()
            coEvery { resolver.resolve(any()) } coAnswers {
                delay(SLOW_LOOKUP_MS)
                AppResult.Success(source.copy(itemId = nextItemId))
            }

            model.playNextEpisode()
            advanceTimeBy(SLOW_LOOKUP_MS / 2)
            playerHandle.emit(PlayerEvent.Ended)
            advanceTimeBy(1L)

            // Asserted here, while the replacement is still being negotiated: this is the window in
            // which `PlayerScreen` would have popped the route, and the later open republishes the
            // flag anyway — so an assertion made after it would pass with or without the guard.
            model.uiState.value.hasEnded shouldBe false

            advanceUntilIdle()
            model.uiState.value.hasEnded shouldBe false
            playerHandle.prepared.last().playWhenReady shouldBe true
        }

    @Test
    fun `an episode that plays out advances to its successor on its own`() =
        runTest(dispatcher) {
            // A natural solo end with a successor prefetched is the middle of a binge, not an exit.
            // The screen must not pop — the same session carries straight into the next episode.
            val model = offering()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))

            playerHandle.emit(PlayerEvent.Ended)
            // Before the open completes: this is the frame `PlayerScreen` would have popped on.
            model.uiState.value.hasEnded shouldBe false

            advanceUntilIdle()
            model.uiState.value.hasEnded shouldBe false
            playerHandle.prepared.last().playWhenReady shouldBe true
        }

    @Test
    fun `the end advances even when the card never showed`() =
        runTest(dispatcher) {
            // Backgrounded playback: the ticker stops with the screen, so the card may never have
            // been offered at all — but the episode still ends, and the binge still continues.
            coEvery { upNextResolver.resolve(any()) } returns nextEpisode
            val model = viewModel()
            advanceUntilIdle()
            model.card.shouldBeNull()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe false
            playerHandle.prepared.last().playWhenReady shouldBe true
        }

    @Test
    fun `a film that plays out still closes the screen`() =
        runTest(dispatcher) {
            // No successor, no advance: the resolver's `null` is what "nothing follows this" means,
            // and the pre-feature exit stands for every non-episode and every last episode.
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe true
        }

    @Test
    fun `a dismissed card means the end closes the screen as before`() =
        runTest(dispatcher) {
            // "Watch credits" is the user declining the next episode, and it holds through to the
            // end — advancing anyway would make the dismissal a lie.
            val model = offering()
            model.dismissUpNext()
            playerHandle.resetCalls()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe true
            playerHandle.prepared.shouldBeEmpty()
        }

    @Test
    fun `the automatic advance reports the outgoing episode exactly once`() =
        runTest(dispatcher) {
            // `Ended` sends the detached report and arms the guard before the advance is triggered
            // (`viewModelScope` is `Main.immediate` — an advance launched earlier would race it);
            // the swap's own `endCurrentSource` must then stay quiet.
            val model = offering()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            coVerify(exactly = 1) { reporter.reportStopDetached(source, any()) }
            coVerify(exactly = 0) { reporter.reportStop(source, any()) }
            model.uiState.value.hasEnded shouldBe false
        }

    // ---- the one-stop-report-per-source invariant --------------------------------------------------

    @Test
    fun `a tap before the end reports the outgoing episode exactly once`() =
        runTest(dispatcher) {
            val model = offering()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))

            model.playNextEpisode()
            advanceUntilIdle()

            coVerify(exactly = 1) { reporter.reportStop(source, any()) }
            coVerify(exactly = 0) { reporter.reportStopDetached(source, any()) }
        }

    @Test
    fun `a swap after the end does not report the outgoing episode twice`() =
        runTest(dispatcher) {
            // An undismissed end advances by itself — that order has its own pin above — so the
            // dismissal is what arranges an episode that ended without advancing, with a swap
            // arriving after. `endCurrentSource`'s idempotence is what this pins, whatever
            // triggers the swap.
            val model = offering()
            model.dismissUpNext()
            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(itemId = nextItemId))

            model.playNextEpisode()
            advanceUntilIdle()

            // The detached report went out with `Ended`; the swap's own `endCurrentSource` finds the
            // guard already armed and stays quiet.
            coVerify(exactly = 1) { reporter.reportStopDetached(source, any()) }
            coVerify(exactly = 0) { reporter.reportStop(source, any()) }
        }

    // ---- fixture ----------------------------------------------------------------------------------

    /** A ViewModel playing an episode whose successor is known and whose card is on screen. */
    private fun offering(): PlayerViewModel {
        coEvery { upNextResolver.resolve(any()) } returns nextEpisode
        coEvery { segmentLoader.load(any()) } returns listOf(outro)
        val model = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        model.onTick(PlaybackSnapshot(positionMs = OUTRO_START_MS))
        return model
    }

    /** The card as the screen would see it — a named reading, so the assertions stay one line. */
    private val PlayerViewModel.card: UpNextState? get() = uiState.value.upNext

    /** The skip-intro/outro button the card competes with. */
    private val PlayerViewModel.skipButton: MediaSegment? get() = uiState.value.skippableSegment

    private fun inGroup() =
        SyncPlayState.InGroup(
            group = group(),
            queue = null,
            groupState = SyncPlayGroupState.Paused,
            phase = SyncPlayPhase.Paused,
        )

    private companion object {
        /** The fixture item's runtime in milliseconds — `PlayerFixtures.RUN_TIME_TICKS`, two hours. */
        const val DURATION_MS = 7_200_000L

        /** Where the fixture's outro starts: a hundred seconds from the end. */
        const val OUTRO_START_MS = DURATION_MS - 100_000L

        /** Where the card appears on an item the segments API knows nothing about. */
        const val FALLBACK_START_MS = DURATION_MS - 60_000L

        /** Long enough that nothing completes it by accident; the tests advance past it explicitly. */
        const val SLOW_LOOKUP_MS = 5_000L
    }
}
