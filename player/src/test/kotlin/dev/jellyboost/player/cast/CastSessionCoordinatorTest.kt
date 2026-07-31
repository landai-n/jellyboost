package dev.jellyboost.player.cast

import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.FakePlayerHandle
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
import javax.inject.Provider

/**
 * Unit tests for [CastSessionCoordinator].
 *
 * The Cast framework's session lifecycle is behind [CastSessionMonitor] precisely so this can be
 * pinned without Play services, a `CastContext` or a receiver on the network — and what is pinned is
 * entirely ours: which player is in charge, and the invariant that stops the server being told twice
 * that a film stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastSessionCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    private val local = FakePlayerHandle()
    private val cast = FakePlayerHandle()
    private val routing = RoutingPlayerHandle(local, Provider { cast })

    private val reporter = mockk<PlaybackReporter>(relaxed = true)
    private val status = CastStatusHolder()

    /** Captures the coordinator's listener so a test can be the Cast framework. */
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
            status = status,
            detachedScope = CoroutineScope(dispatcher),
            mainDispatcher = dispatcher,
        ).also { it.start() }

    private val framework get() = requireNotNull(monitor.listener) { "The coordinator never started watching" }

    private val source = PlayerFixtures.remoteSource()

    private val host =
        object : CastPlaybackHost {
            override var castSource: PlaybackMediaSource? = source
        }

    @Test
    fun `nothing is casting until the framework says so`() {
        coordinator.isCasting shouldBe false
        coordinator.connection.value shouldBe CastConnection.None
        routing.activeHandle.value shouldBe local
    }

    @Test
    fun `a session start puts the receiver in charge and names it`() {
        framework.onSessionStarted("Living Room TV")

        coordinator.connection.value shouldBe CastConnection.Connected("Living Room TV")
        coordinator.isCasting shouldBe true
        routing.activeHandle.value shouldBe cast
    }

    @Test
    fun `a session end hands playback back to this device`() {
        framework.onSessionStarted("Living Room TV")

        framework.onSessionEnded()

        coordinator.connection.value shouldBe CastConnection.None
        coordinator.isCasting shouldBe false
        routing.activeHandle.value shouldBe local
    }

    @Test
    fun `with a screen attached the coordinator reports nothing — the screen does`() {
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)

        framework.onSessionEnded()

        // Two stop reports would double the encoder kill and race each other for the position.
        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `once the screen goes the coordinator takes the progress ticker over`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)

        coordinator.detachHost(host)

        verify(exactly = 1) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `a screen that goes with nothing casting starts no ticker`() {
        coordinator.attachHost(host)

        coordinator.detachHost(host)

        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `a host with nothing open leaves nothing to report`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        host.castSource = null

        coordinator.detachHost(host)

        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `a stale screen's teardown cannot detach the one that replaced it`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)

        coordinator.detachHost(
            object : CastPlaybackHost {
                override val castSource: PlaybackMediaSource? = source
            },
        )

        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `the ticker stops when a screen comes back`() {
        val ticker = Job()
        every { reporter.startReporting(any(), any(), any()) } returns ticker
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        coordinator.attachHost(host)

        ticker.isCancelled shouldBe true
    }

    @Test
    fun `a session that ends with no screen sends the final stop itself`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        val onTheTelevision = PlaybackSnapshot(positionMs = 90_000L, isPlaying = true)
        cast.snapshot = onTheTelevision
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        framework.onSessionEnded()

        // The position has to be read off the *cast* player, before routing goes back to a local one
        // that has never played anything. `reportStopDetached` carries the encoder kill with it.
        verify(exactly = 1) { reporter.reportStopDetached(source, onTheTelevision) }
        routing.activeHandle.value shouldBe local
    }

    @Test
    fun `the ticker is cancelled when the session ends`() {
        val ticker = Job()
        every { reporter.startReporting(any(), any(), any()) } returns ticker
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        framework.onSessionEnded()

        ticker.isCancelled shouldBe true
    }

    @Test
    fun `a resumed session is a started one, since a receiver that is playing is a receiver`() {
        framework.onSessionStarted(null)

        coordinator.connection.value shouldBe CastConnection.Connected(null)
        routing.activeHandle.value shouldBe cast
    }
}
