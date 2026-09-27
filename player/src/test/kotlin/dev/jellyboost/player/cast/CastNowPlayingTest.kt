package dev.jellyboost.player.cast

import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.RoutingPlayerHandle
import dev.jellyboost.player.session.tapPlays
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * The casting bar's source, over a real coordinator and routing handle and a fake monitor — the same
 * assembly as `CastSessionCoordinatorTest`, so "what the bar shows" is checked against the very state
 * the reports are sent from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastNowPlayingTest {
    private val dispatcher = StandardTestDispatcher()

    private val local = FakePlayerHandle()
    private val cast = FakePlayerHandle()
    private val routing = RoutingPlayerHandle(local, Provider { cast })
    private val reporter = mockk<PlaybackReporter>(relaxed = true)
    private val metadata = CastMetadataHolder()

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
            metadata = metadata,
        ).also { it.start() }

    private val nowPlaying = CastNowPlaying(coordinator, CoroutineScope(dispatcher), dispatcher)

    private val framework get() = requireNotNull(monitor.listener)

    private val source = PlayerFixtures.remoteSource()

    private val host =
        object : CastPlaybackHost {
            override val castSource: PlaybackMediaSource? = source
        }

    /** The screen was open, casting, and has gone: what the bar exists for. */
    private fun leftPlaying() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        metadata.publish(
            source.itemId.toString(),
            CastMetadata(title = "Arrival", subtitle = "2016", posterUrl = POSTER),
        )
        cast.snapshot = ON_THE_TELEVISION
        cast.playWhenReady = true
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)
    }

    /** Subscribes like the chrome does; the flow only polls while someone is looking. */
    private fun TestScope.watch() {
        backgroundScope.launch { nowPlaying.state.collect {} }
        runCurrent()
    }

    @Test
    fun `nothing is shown with nothing cast`() =
        runTest(dispatcher) {
            watch()

            nowPlaying.state.value shouldBe null
            nowPlaying.current() shouldBe null
        }

    @Test
    fun `nothing is shown while the player screen is attached — it is the remote control`() =
        runTest(dispatcher) {
            framework.onSessionStarted("Living Room TV")
            coordinator.attachHost(host)
            watch()

            nowPlaying.state.value shouldBe null
        }

    @Test
    fun `a film left playing on the television is shown with its title, artwork, device and position`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()

            nowPlaying.state.value shouldBe
                CastingItem(
                    itemId = source.itemId.toString(),
                    title = "Arrival",
                    subtitle = "2016",
                    artworkUrl = POSTER,
                    deviceName = "Living Room TV",
                    isReconnecting = false,
                    playWhenReady = true,
                    isBuffering = false,
                    positionMs = ON_THE_TELEVISION.positionMs,
                    durationMs = ON_THE_TELEVISION.durationMs,
                )
            nowPlaying.current() shouldBe nowPlaying.state.value
        }

    @Test
    fun `the position follows the television while someone is looking`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()

            cast.snapshot = ON_THE_TELEVISION.copy(positionMs = 905_000L)
            advanceTimeBy(1_001L)
            runCurrent()

            nowPlaying.state.value?.positionMs shouldBe 905_000L
        }

    @Test
    fun `an invalid reading keeps the last position rather than springing back to zero`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()

            cast.snapshot = PlaybackSnapshot(isValid = false)
            advanceTimeBy(1_001L)
            runCurrent()

            nowPlaying.state.value?.positionMs shouldBe ON_THE_TELEVISION.positionMs
        }

    @Test
    fun `the toggle pauses the television, and the bar says so at once`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()

            nowPlaying.togglePlayPause()
            runCurrent()

            cast.pauseCount shouldBe 1
            nowPlaying.state.value?.playWhenReady shouldBe false

            nowPlaying.togglePlayPause()
            runCurrent()

            cast.playCount shouldBe 1
            nowPlaying.state.value?.playWhenReady shouldBe true
        }

    @Test
    fun `a television settled paused under a stale intent is shown as one the tap plays, and the tap plays it`() =
        runTest(dispatcher) {
            leftPlaying()
            cast.isSettledPaused = true
            watch()
            val shown = requireNotNull(nowPlaying.state.value)

            nowPlaying.togglePlayPause()
            runCurrent()

            shown.isSettledPaused shouldBe true
            // What the bar labels its button from, and what the tap just did: one rule.
            tapPlays(shown.playWhenReady, shown.isSettledPaused) shouldBe true
            cast.playCount shouldBe 1
            cast.pauseCount shouldBe 0
        }

    @Test
    fun `a buffering receiver is shown as buffering, however its reading looks`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()
            cast.snapshot = PlaybackSnapshot(isValid = false)

            cast.tryEmit(PlayerEvent.Buffering(true))
            runCurrent()

            nowPlaying.state.value?.isBuffering shouldBe true
            nowPlaying.state.value?.playWhenReady shouldBe true
        }

    @Test
    fun `a suspended session says it is reconnecting`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()

            framework.onSessionSuspended()
            runCurrent()

            nowPlaying.state.value?.isReconnecting shouldBe true
        }

    @Test
    fun `the bar goes when the session ends, and when a screen takes the film back`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()

            coordinator.attachHost(host)
            runCurrent()
            nowPlaying.state.value shouldBe null

            coordinator.detachHost(host)
            runCurrent()
            nowPlaying.state.value?.itemId shouldBe source.itemId.toString()

            framework.onSessionEnded()
            runCurrent()
            nowPlaying.state.value shouldBe null
        }

    @Test
    fun `the bar's resume position is where the television is, in ticks`() =
        runTest(dispatcher) {
            leftPlaying()
            watch()

            nowPlaying.state.value?.positionTicks shouldBe ON_THE_TELEVISION.positionTicks
        }

    private companion object {
        const val POSTER = "https://server/Items/x/Images/Backdrop"

        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, durationMs = 7_200_000L, isPlaying = true)
    }
}
