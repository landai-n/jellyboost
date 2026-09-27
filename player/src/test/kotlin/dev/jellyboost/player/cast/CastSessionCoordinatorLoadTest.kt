package dev.jellyboost.player.cast

import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.RoutingPlayerHandle
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.inject.Provider
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [CastSessionCoordinator] takes to be the detached source's: the same **load** the receiver holds,
 * however its selected tracks have changed in place since; and what it does when the receiver plays that
 * film to its end with no screen open. Split from [CastSessionCoordinatorTest] for size; same fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastSessionCoordinatorLoadTest {
    private val dispatcher = StandardTestDispatcher()

    private val local = FakePlayerHandle()
    private val cast = FakePlayerHandle()
    private val routing = RoutingPlayerHandle(local, Provider { cast })

    private val reporter = mockk<PlaybackReporter>(relaxed = true)

    private val monitor =
        object : CastSessionMonitor {
            var listener: CastSessionListener? = null

            override fun start(listener: CastSessionListener) {
                this.listener = listener
            }
        }

    private val coordinator =
        CastSessionCoordinator(
            monitor = monitor,
            routing = routing,
            reporter = reporter,
            status = CastStatusHolder(),
            detachedScope = CoroutineScope(dispatcher),
            mainDispatcher = dispatcher,
        ).also { it.start() }

    private val framework get() = requireNotNull(monitor.listener) { "The coordinator never started watching" }

    private val source = PlayerFixtures.remoteSource()

    private val host =
        object : CastPlaybackHost {
            override var castSource: PlaybackMediaSource? = source
        }

    private fun receiverSays(event: PlayerEvent) {
        dispatcher.scheduler.runCurrent()
        cast.tryEmit(event)
        dispatcher.scheduler.runCurrent()
    }

    private fun elapse(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms.milliseconds)
        dispatcher.scheduler.runCurrent()
    }

    private fun castingDetachedAt(at: PlaybackSnapshot) {
        cast.snapshot = at
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)
    }

    private fun screenFor(opened: PlaybackMediaSource?) =
        object : CastPlaybackHost {
            override val castSource: PlaybackMediaSource? = opened
        }

    // ---- the same load, with a track chosen in place ----------------------------------------------------

    /**
     * A track chosen in place on the receiver (subtitles off) hands the coordinator a *copy* of the source
     * it loaded: the same load, whose readings are still the detached one's.
     */
    @Test
    fun `a source whose track was chosen in place is still read as the detached one`() {
        cast.snapshot = ON_THE_TELEVISION
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        cast.preparedSource = source
        val inPlace = screenFor(source.withSelectedSubtitle(null))
        coordinator.attachHost(inPlace)
        coordinator.detachHost(inPlace)
        val later = ON_THE_TELEVISION.copy(positionMs = 960_000L)
        cast.snapshot = later

        coordinator.readReceiver() shouldBe later
        coordinator.heldSourceFor(source.itemId)?.reading shouldBe later
    }

    @Test
    fun `a screen adopting the load with a track since chosen in place is not an orphan`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.preparedSource = source

        coordinator.attachHost(screenFor(source.withSelectedSubtitle(3)))

        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    // ---- a detached film played to its end ------------------------------------------------------------

    @Test
    fun `a detached film the receiver finished is closed once, at its ended reading`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.preparedSource = source
        val finished = ON_THE_TELEVISION.copy(positionMs = 7_200_000L, isPlaying = false, hasEnded = true)
        cast.snapshot = finished

        receiverSays(PlayerEvent.Ended)

        verify(exactly = 1) { reporter.reportStopDetached(source, finished) }
        coordinator.detached.value shouldBe null
        coordinator.heldSourceFor(source.itemId) shouldBe null
        // A natural end is not a stop: the casting bar's exit announcement tells them apart.
        coordinator.lastDetachExit shouldBe CastExitReason.FINISHED

        framework.onSessionEnded()

        verify(exactly = 1) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `a finish noticed after the item went missing cancels the loss`() {
        castingDetachedAt(ON_THE_TELEVISION)
        receiverSays(PlayerEvent.RemoteItemMissing(ON_THE_TELEVISION))
        val finished = ON_THE_TELEVISION.copy(positionMs = 7_200_000L, isPlaying = false, hasEnded = true)
        cast.snapshot = finished

        receiverSays(PlayerEvent.RemoteItemMissingCleared)
        receiverSays(PlayerEvent.Ended)
        elapse(GRACE_MS + 1L)

        verify(exactly = 1) { reporter.reportStopDetached(any(), any()) }
        verify(exactly = 1) { reporter.reportStopDetached(source, finished) }
    }

    @Test
    fun `with a screen attached the receiver's finish is the screen's to report`() {
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        cast.snapshot = ON_THE_TELEVISION.copy(hasEnded = true)

        receiverSays(PlayerEvent.Ended)

        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `the end of a film a new screen has loaded is not the detached one's`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.preparedSource = PlayerFixtures.remoteSource().copy(itemId = OTHER_ITEM)
        cast.snapshot = ON_THE_TELEVISION.copy(hasEnded = true)

        receiverSays(PlayerEvent.Ended)

        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    private companion object {
        val GRACE_MS = CastSessionCoordinator.ITEM_LOST_GRACE.inWholeMilliseconds

        /** Fifteen minutes in, on the television. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, durationMs = 7_200_000L, isPlaying = true)

        val OTHER_ITEM: UUID = UUID.fromString("9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4")
    }
}
