package dev.jellyfinnative.player.syncplay.ui

import app.cash.turbine.test
import dev.jellyfinnative.player.syncplay.SyncPlayController
import dev.jellyfinnative.player.syncplay.SyncPlayLaunchRequest
import dev.jellyfinnative.player.syncplay.SyncPlayMessage
import dev.jellyfinnative.player.syncplay.SyncPlayPhase
import dev.jellyfinnative.player.syncplay.SyncPlayState
import dev.jellyfinnative.player.syncplay.api.SyncPlayApi
import dev.jellyfinnative.player.syncplay.group
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyfinnative.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayShuffleMode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [SyncPlayGroupsViewModel].
 *
 * Two things carry this suite. First, the poll: it must run on its own 10 s cadence, treat HTTP 403
 * as permanent (docs/notes/syncplay-m11-plan.md, "Phase 5" — "SyncPlay is disabled for your
 * account") and treat everything else as one tick's worth of bad luck. Second, every membership
 * action is a one-line forward to [dev.jellyfinnative.player.syncplay.SyncPlayController] — this
 * class owns no socket and no join handshake of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPlayGroupsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val groupA = groupSummary(id = UUID.fromString("00000000-0000-0000-0000-0000000000a1"), name = "Film night")
    private val groupB = groupSummary(id = UUID.fromString("00000000-0000-0000-0000-0000000000a2"), name = "Rewatch")

    private val controllerState = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)
    private val controllerMessages = MutableSharedFlow<SyncPlayMessage>(extraBufferCapacity = 8)
    private val controller =
        mockk<SyncPlayController>(relaxed = true) {
            every { state } returns controllerState
            every { messages } returns controllerMessages
        }
    private val api = mockk<SyncPlayApi>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the group list is fetched as soon as the screen is visible`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } returns listOf(groupA)
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem().isLoading shouldBe true

                val loaded = awaitItem()
                loaded.isLoading shouldBe false
                loaded.groups shouldBe listOf(groupA)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { api.getGroups() }
        }

    @Test
    fun `the list re-polls every 10 seconds`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } returns listOf(groupA) andThen listOf(groupA, groupB)
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem() // isLoading = true
                awaitItem().groups shouldBe listOf(groupA)

                dispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
                dispatcher.scheduler.runCurrent()
                awaitItem().groups shouldBe listOf(groupA, groupB)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 2) { api.getGroups() }
        }

    @Test
    fun `a 403 disables the screen and the poll never asks again`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } throws InvalidStatusException(status = 403)
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem() // isLoading = true
                val disabled = awaitItem()
                disabled.disabled shouldBe true
                disabled.groups shouldBe emptyList()

                dispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS * 3)
                dispatcher.scheduler.runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { api.getGroups() }
        }

    @Test
    fun `a transient failure recovers on the next tick`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } throws IOException("no route to host") andThen listOf(groupA)
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem() // isLoading = true
                val failed = awaitItem()
                failed.transientError shouldBe true
                failed.groups shouldBe emptyList()

                dispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
                dispatcher.scheduler.runCurrent()
                val recovered = awaitItem()
                recovered.transientError shouldBe false
                recovered.groups shouldBe listOf(groupA)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 2) { api.getGroups() }
        }

    @Test
    fun `retry does not wait out the rest of the tick`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } throws IOException("blip") andThen listOf(groupA)
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                awaitItem().transientError shouldBe true

                viewModel.retry()
                dispatcher.scheduler.runCurrent()
                val recovered = awaitItem()
                recovered.transientError shouldBe false
                recovered.groups shouldBe listOf(groupA)
                cancelAndIgnoreRemainingEvents()
            }
            // Recovered well inside the 10 s tick the retry skipped past.
            coVerify(exactly = 2) { api.getGroups() }
        }

    @Test
    fun `join forwards straight to the controller`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } returns emptyList()
            val viewModel = viewModel()

            viewModel.join(groupA)

            verify { controller.joinGroup(groupA) }
        }

    @Test
    fun `create trims the name and drops a blank one`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } returns emptyList()
            val viewModel = viewModel()

            viewModel.createGroup("  Film night  ")
            viewModel.createGroup("   ")

            verify(exactly = 1) { controller.createGroup("Film night") }
        }

    @Test
    fun `leave forwards straight to the controller`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } returns emptyList()
            val viewModel = viewModel()

            viewModel.leave()

            verify { controller.leaveGroup() }
        }

    @Test
    fun `membership mirrors the controller, and the active group leaves the browsable list`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } returns listOf(groupA, groupB)
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                val idle = awaitItem()
                idle.membership shouldBe SyncPlayGroupsMembership.None
                idle.groups shouldBe listOf(groupA, groupB)

                controllerState.value = SyncPlayState.Joining
                awaitItem().membership shouldBe SyncPlayGroupsMembership.Joining

                controllerState.value =
                    SyncPlayState.InGroup(group = groupA, queue = playingQueue(), phase = SyncPlayPhase.Paused)
                val inGroup = awaitItem()
                val membership = inGroup.membership
                check(membership is SyncPlayGroupsMembership.InGroup)
                membership.groupId shouldBe groupA.id
                membership.groupName shouldBe groupA.name
                membership.openPlayer shouldBe SyncPlayLaunchRequest(itemId = playingItemId, startPositionTicks = 0L)
                // groupA is this device's own group now — the browsable list shows only groupB.
                inGroup.groups shouldBe listOf(groupB)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a controller message becomes the one-shot user message, and consuming clears it`() =
        runTest(dispatcher) {
            coEvery { api.getGroups() } returns emptyList()
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                awaitItem().userMessage shouldBe null

                controllerMessages.emit(SyncPlayMessage.JoinFailed)
                awaitItem().userMessage shouldBe SyncPlayMessage.JoinFailed

                viewModel.consumeMessage()
                awaitItem().userMessage shouldBe null
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun viewModel() = SyncPlayGroupsViewModel(api, controller)

    private val playingItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

    private fun playingQueue() =
        SyncPlayGroupQueue(
            entries = listOf(SyncPlayQueueEntry(itemId = playingItemId, playlistItemId = UUID.randomUUID())),
            playingItemIndex = 0,
            startPositionTicks = 0L,
            isPlaying = true,
            shuffleMode = SyncPlayShuffleMode.Sorted,
            repeatMode = SyncPlayRepeatMode.None,
            reason = SyncPlayQueueUpdateReason.NewPlaylist,
            lastUpdate = Instant.parse("2026-07-30T18:00:00Z"),
        )

    private fun groupSummary(
        id: UUID,
        name: String,
    ): SyncPlayGroupSummary = group(id = id, name = name, participants = listOf("casey"))

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
    }
}
