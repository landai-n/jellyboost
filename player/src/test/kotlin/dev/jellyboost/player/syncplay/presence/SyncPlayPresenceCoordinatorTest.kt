package dev.jellyboost.player.syncplay.presence

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.jellyboost.player.session.PlaybackServiceState
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SyncPlayPresenceCoordinator]'s own wiring — the debounce cadence, the
 * idempotent [SyncPlayPresenceCoordinator.start], and the `ON_START` re-check.
 *
 * The pure demand rule is pinned separately in [SyncPlayGroupPresenceTest]; what these tests hold
 * still is *when* the service is asked to start or stop. The two failure shapes are both silent on
 * a device: churn (a notification that flashes and vanishes, which the platform penalises) and a
 * missed settled demand (a group quietly dropped when the process loses its network).
 *
 * The service calls themselves are stubbed at the coordinator's own private seam — the real
 * bodies end in `ContextCompat`/`Context` framework calls that a JVM test cannot make, and the
 * cadence, not the `Intent`, is what needs pinning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPlayPresenceCoordinatorTest {
    private val stateFlow = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)
    private val controller =
        mockk<SyncPlayController> {
            every { state } returns stateFlow
            justRun { onAppForegrounded() }
        }
    private val playbackServiceState = PlaybackServiceState()
    private val context = mockk<Context>(relaxed = true)
    private val processLifecycle = mockk<Lifecycle>(relaxUnitFun = true)

    @BeforeEach
    fun setUp() {
        mockkObject(ProcessLifecycleOwner.Companion)
        val owner = mockk<LifecycleOwner> { every { lifecycle } returns processLifecycle }
        every { ProcessLifecycleOwner.get() } returns owner
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ProcessLifecycleOwner.Companion)
    }

    @Test
    fun `a demand that holds for the settle window starts the service, once`() =
        runTest {
            stateFlow.value = inGroup()
            val coordinator = coordinator()
            coordinator.start()

            advanceTimeBy(SETTLE_MS - 1)
            runCurrent()
            verify(exactly = 0) { coordinator["startPresenceService"]() }

            advanceTimeBy(1)
            runCurrent()
            verify(exactly = 1) { coordinator["startPresenceService"]() }
            verify(exactly = 0) { coordinator["stopPresenceService"]() }
        }

    @Test
    fun `a demand that reverts inside the settle window causes no service churn`() =
        runTest {
            val coordinator = coordinator()
            coordinator.start()
            // Let the initial no-demand settle so the baseline stop is out of the way.
            advanceTimeBy(SETTLE_MS)
            runCurrent()
            verify(exactly = 1) { coordinator["stopPresenceService"]() }

            // A join that fails (or hands over) in half the settle window: the service must
            // neither start nor be stopped a second time — this is the flash-then-vanish
            // notification the debounce exists to prevent.
            stateFlow.value = inGroup()
            advanceTimeBy(SETTLE_MS / 2)
            runCurrent()
            stateFlow.value = SyncPlayState.Idle
            advanceTimeBy(SETTLE_MS * 3)
            runCurrent()

            verify(exactly = 0) { coordinator["startPresenceService"]() }
            verify(exactly = 1) { coordinator["stopPresenceService"]() }
        }

    @Test
    fun `playback taking over releases the service after the settle window`() =
        runTest {
            stateFlow.value = inGroup()
            val coordinator = coordinator()
            coordinator.start()
            advanceTimeBy(SETTLE_MS)
            runCurrent()
            verify(exactly = 1) { coordinator["startPresenceService"]() }

            playbackServiceState.setRunning(true)
            advanceTimeBy(SETTLE_MS)
            runCurrent()
            verify(exactly = 1) { coordinator["stopPresenceService"]() }

            // And ending playback while still in the group takes it back.
            playbackServiceState.setRunning(false)
            advanceTimeBy(SETTLE_MS)
            runCurrent()
            verify(exactly = 2) { coordinator["startPresenceService"]() }
        }

    @Test
    fun `start is idempotent — one observer, one collector`() =
        runTest {
            stateFlow.value = inGroup()
            val coordinator = coordinator()
            coordinator.start()
            coordinator.start()

            verify(exactly = 1) { processLifecycle.addObserver(coordinator) }
            // A duplicated collector would double every service call.
            advanceTimeBy(SETTLE_MS)
            runCurrent()
            verify(exactly = 1) { coordinator["startPresenceService"]() }
        }

    @Test
    fun `coming to the foreground re-checks a settled demand immediately`() =
        runTest {
            stateFlow.value = inGroup()
            val coordinator = coordinator()

            coordinator.onStart(mockk())

            // No debounce on this path: the app is on screen, the start is allowed right now.
            verify(exactly = 1) { controller.onAppForegrounded() }
            verify(exactly = 1) { coordinator["startPresenceService"]() }
        }

    @Test
    fun `coming to the foreground with no demand pings the controller and nothing else`() =
        runTest {
            val coordinator = coordinator()

            coordinator.onStart(mockk())

            verify(exactly = 1) { controller.onAppForegrounded() }
            verify(exactly = 0) { coordinator["startPresenceService"]() }
            verify(exactly = 0) { coordinator["stopPresenceService"]() }
        }

    /** A spy whose two private service calls are stubbed and recorded; the wiring stays real. */
    private fun TestScope.coordinator(): SyncPlayPresenceCoordinator {
        val coordinator =
            spyk(
                SyncPlayPresenceCoordinator(context, controller, playbackServiceState, backgroundScope),
                recordPrivateCalls = true,
            )
        every { coordinator["startPresenceService"]() } returns Unit
        every { coordinator["stopPresenceService"]() } returns Unit
        return coordinator
    }

    private fun inGroup() =
        SyncPlayState.InGroup(
            group(),
            queue = null,
            groupState = SyncPlayGroupState.Idle,
            phase = SyncPlayPhase.Waiting,
        )

    private companion object {
        /** Mirrors the coordinator's private `DEMAND_SETTLE_MS`; a change there should be felt here. */
        const val SETTLE_MS = 400L
    }
}
