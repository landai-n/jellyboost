package dev.jellyboost.player.syncplay.ui

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [SyncPlayQueueViewModel].
 *
 * Two claims carry the sheet. The first is that the rows are the *group's* queue with names hung on
 * it — the protocol carries ids and nothing else, so a row is only readable once the repository has
 * been asked, and it must be asked once per item however often the queue is re-sent. The second is
 * key decision 11 again: every edit is a request, and this class must therefore change nothing
 * locally when one is made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPlayQueueViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val firstItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val secondItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val firstSlot = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val secondSlot = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

    private val controllerState = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)
    private val controller =
        mockk<SyncPlayController>(relaxed = true) {
            every { state } returns controllerState
        }
    private val repository = mockk<JellyfinRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getItem(firstItemId.toString()) } returns
            AppResult.Success(item(firstItemId, "The Original"))
        coEvery { repository.getItem(secondItemId.toString()) } returns
            AppResult.Success(item(secondItemId, "Chestnut"))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `outside a group there is nothing to draw`() =
        runTest(dispatcher) {
            viewModel().uiState.test {
                awaitItem().isEmpty shouldBe true
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `entries arrive as ids and become rows with titles`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                controllerState.value = inGroup(queue(playingIndex = 0))

                val hydrated = awaitUntil { rows -> rows.rows.all { it.title != null } }
                hydrated.rows.map { it.title } shouldBe listOf("The Original", "Chestnut")
                hydrated.rows.map { it.playlistItemId } shouldBe listOf(firstSlot, secondSlot)
                hydrated.rows.map { it.isPlaying } shouldBe listOf(true, false)
                hydrated.playingIndex shouldBe 0
                cancelAndIgnoreRemainingEvents()
            }

            // The queue is re-sent on every edit; the items behind it are fetched once.
            coVerify(exactly = 1) { repository.getItem(firstItemId.toString()) }
        }

    @Test
    fun `a reorder redraws from what was already fetched`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                controllerState.value = inGroup(queue(playingIndex = 0))
                awaitUntil { rows -> rows.rows.all { it.title != null } }

                controllerState.value =
                    inGroup(
                        queue(playingIndex = 1).let { it.copy(entries = it.entries.reversed()) },
                    )
                val reordered = awaitUntil { it.rows.firstOrNull()?.title == "Chestnut" }

                reordered.rows.map { it.title } shouldBe listOf("Chestnut", "The Original")
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { repository.getItem(firstItemId.toString()) }
            coVerify(exactly = 1) { repository.getItem(secondItemId.toString()) }
        }

    @Test
    fun `an entry the server will not describe still holds its place in the queue`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(secondItemId.toString()) } returns
                AppResult.Failure(AppError.NotFound(secondItemId.toString()))
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                controllerState.value = inGroup(queue(playingIndex = 0))

                val rows = awaitUntil { it.rows.firstOrNull()?.title != null }
                rows.rows.map { it.title } shouldBe listOf("The Original", null)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `every edit is a request to the group and touches nothing here`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            controllerState.value = inGroup(queue(playingIndex = 0))
            viewModel.uiState.test {
                awaitUntil { it.rows.size == 2 }
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.play(secondSlot)
            viewModel.move(secondSlot, 0)
            viewModel.remove(firstSlot)
            viewModel.next()
            viewModel.previous()

            verify { controller.requestSetPlaylistItem(secondSlot) }
            verify { controller.moveQueueItem(secondSlot, 0) }
            verify { controller.removeFromQueue(listOf(firstSlot)) }
            verify { controller.requestNext() }
            verify { controller.requestPrevious() }
            // The queue on screen is the server's; nothing above changed a row.
            viewModel.uiState.value.rows
                .map { it.playlistItemId } shouldBe listOf(firstSlot, secondSlot)
        }

    @Test
    fun `a move off either end of the queue is not sent at all`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            controllerState.value = inGroup(queue(playingIndex = 0))
            viewModel.uiState.test {
                awaitUntil { it.rows.size == 2 }
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.move(firstSlot, -1)
            viewModel.move(secondSlot, 2)

            verify(exactly = 0) { controller.moveQueueItem(any(), any()) }
        }

    @Test
    fun `next and previous are offered only where the queue actually goes`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                controllerState.value = inGroup(queue(playingIndex = 0))
                val first = awaitUntil { it.rows.size == 2 }
                first.hasNext shouldBe true
                first.hasPrevious shouldBe false

                controllerState.value = inGroup(queue(playingIndex = 1))
                val last = awaitUntil { it.playingIndex == 1 }
                last.hasNext shouldBe false
                last.hasPrevious shouldBe true
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun viewModel() = SyncPlayQueueViewModel(controller, repository)

    /** Awaits the first emission satisfying [predicate] — hydration lands over several of them. */
    private suspend fun app.cash.turbine.ReceiveTurbine<SyncPlayQueueUiState>.awaitUntil(
        predicate: (SyncPlayQueueUiState) -> Boolean,
    ): SyncPlayQueueUiState {
        while (true) {
            val next = awaitItem()
            if (predicate(next)) return next
        }
    }

    private fun inGroup(queue: SyncPlayGroupQueue) =
        SyncPlayState.InGroup(
            group = group(),
            queue = queue,
            groupState = SyncPlayGroupState.Paused,
            phase = SyncPlayPhase.Paused,
        )

    private fun queue(playingIndex: Int) =
        SyncPlayGroupQueue(
            entries =
                listOf(
                    SyncPlayQueueEntry(firstItemId, firstSlot),
                    SyncPlayQueueEntry(secondItemId, secondSlot),
                ),
            playingItemIndex = playingIndex,
            startPositionTicks = 0L,
            isPlaying = false,
            shuffleMode = SyncPlayShuffleMode.Sorted,
            repeatMode = SyncPlayRepeatMode.None,
            reason = SyncPlayQueueUpdateReason.NewPlaylist,
            lastUpdate = Instant.parse("2026-07-30T18:00:00Z"),
        )

    private fun item(
        id: UUID,
        name: String,
    ) = JellyfinItem(id = id.toString(), name = name, type = ItemType.EPISODE)
}
