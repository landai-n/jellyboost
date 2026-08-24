package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.selection.BatchOutcome
import dev.jellyboost.core.common.selection.BatchReport
import dev.jellyboost.core.common.selection.SelectionAction
import dev.jellyboost.core.common.selection.SelectionIntent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Batch selection over the season page's episode list (docs/features/batch-selection.md).
 * Deliberately does not stub `getEpisodes` in a `@BeforeEach`: every test calls
 * [givenSeasonWithEpisodes] first, so a shared default would only ever be overwritten.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ItemDetailSelectionTest : ItemDetailViewModelFixture() {
    @Test
    fun `long-pressing an episode enters selection mode`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            val model = viewModel()
            advanceUntilIdle()

            model.selection.value.isActive shouldBe false
            model.onSelection(SelectionIntent.Toggle(EPISODE_1))

            model.selection.value.isActive shouldBe true
            model.selection.value.count shouldBe 1
            (EPISODE_1 in model.selection.value) shouldBe true
        }

    @Test
    fun `deselecting the last episode leaves selection mode`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.Toggle(EPISODE_1))
            model.onSelection(SelectionIntent.Toggle(EPISODE_1))

            // Mode is derived from emptiness: it cannot be entered with nothing selected.
            model.selection.value.isActive shouldBe false
        }

    @Test
    fun `select all takes every episode the page loaded`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.Toggle(EPISODE_1))
            model.onSelection(SelectionIntent.SelectAll)

            model.selection.value.ids shouldContainExactly setOf(EPISODE_1, EPISODE_2)
        }

    @Test
    fun `clear leaves selection mode and writes nothing`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.Toggle(EPISODE_1))
            model.onSelection(SelectionIntent.Clear)
            advanceUntilIdle()

            model.selection.value.isActive shouldBe false
            coVerify(exactly = 0) { userDataRepository.setPlayed(any(), any()) }
            coVerify(exactly = 0) { downloads.enqueue(any()) }
        }

    @Test
    fun `a background refresh keeps the selection, minus episodes the server dropped`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            val model = viewModel()
            advanceUntilIdle()
            model.onSelection(SelectionIntent.SelectAll)

            coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns
                AppResult.Success(
                    listOf(JellyfinItem(id = EPISODE_1, name = "The Original", type = ItemType.EPISODE)),
                )
            model.refresh()
            advanceUntilIdle()

            // The selection survives a connectivity refresh, but never as an id with no row.
            model.selection.value.ids shouldContainExactly setOf(EPISODE_1)
        }

    @Test
    fun `marking the selection watched writes one user-data call per episode`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.SelectAll)
            model.onSelection(SelectionIntent.Run(SelectionAction.MARK_WATCHED))
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed(EPISODE_1, true) }
            coVerify(exactly = 1) { userDataRepository.setPlayed(EPISODE_2, true) }
            model.uiState.value.userMessage shouldBe
                UserMessage.BatchFinished(
                    BatchReport(SelectionAction.MARK_WATCHED, BatchOutcome(done = 2)),
                )
        }

    @Test
    fun `marking the selection unwatched writes played false`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.Toggle(EPISODE_2))
            model.onSelection(SelectionIntent.Run(SelectionAction.MARK_UNWATCHED))
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed(EPISODE_2, false) }
            coVerify(exactly = 0) { userDataRepository.setPlayed(EPISODE_1, any()) }
        }

    @Test
    fun `a batch runs every item and counts the failures rather than stopping at the first`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            coEvery { userDataRepository.setPlayed(EPISODE_1, any()) } returns
                AppResult.Failure(AppError.Storage())
            coEvery { userDataRepository.setPlayed(EPISODE_2, any()) } returns
                AppResult.Success(UserData(played = true))
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.SelectAll)
            model.onSelection(SelectionIntent.Run(SelectionAction.MARK_WATCHED))
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed(EPISODE_2, true) }
            model.uiState.value.userMessage shouldBe
                UserMessage.BatchFinished(
                    BatchReport(SelectionAction.MARK_WATCHED, BatchOutcome(done = 1, failed = 1)),
                )
        }

    @Test
    fun `downloading the selection skips episodes already on the device`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value = mapOf(EPISODE_1 to DownloadState.Downloaded)
            coEvery { downloads.enqueue(any()) } returns AppResult.Success(Unit)
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.SelectAll)
            model.onSelection(SelectionIntent.Run(SelectionAction.DOWNLOAD))
            advanceUntilIdle()

            // `DownloadEnqueuer` only skips on the container path, so a finished single item would
            // reset to QUEUED and download again.
            coVerify(exactly = 0) { downloads.enqueue(EPISODE_1) }
            coVerify(exactly = 1) { downloads.enqueue(EPISODE_2) }
            model.uiState.value.userMessage shouldBe
                UserMessage.BatchFinished(
                    BatchReport(SelectionAction.DOWNLOAD, BatchOutcome(done = 1, skipped = 1)),
                )
        }

    @Test
    fun `downloading the selection re-enqueues an episode whose download failed`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Failed, EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.enqueue(any()) } returns AppResult.Success(Unit)
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.SelectAll)
            model.onSelection(SelectionIntent.Run(SelectionAction.DOWNLOAD))
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.enqueue(EPISODE_1) }
            coVerify(exactly = 0) { downloads.enqueue(EPISODE_2) }
        }

    @Test
    fun `a failed enqueue is reported, not swallowed`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            coEvery { downloads.enqueue(EPISODE_1) } returns AppResult.Failure(AppError.Network())
            coEvery { downloads.enqueue(EPISODE_2) } returns AppResult.Success(Unit)
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.SelectAll)
            model.onSelection(SelectionIntent.Run(SelectionAction.DOWNLOAD))
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe
                UserMessage.BatchFinished(
                    BatchReport(SelectionAction.DOWNLOAD, BatchOutcome(done = 1, failed = 1)),
                )
        }

    @Test
    fun `selection mode ends as the batch starts`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.SelectAll)
            model.onSelection(SelectionIntent.Run(SelectionAction.MARK_WATCHED))

            // Before `advanceUntilIdle`: the bar is gone, the writes have not run.
            model.selection.value.isActive shouldBe false
            advanceUntilIdle()
            coVerify(exactly = 2) { userDataRepository.setPlayed(any(), true) }
        }

    @Test
    fun `an action with nothing selected does nothing at all`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            val model = viewModel()
            advanceUntilIdle()

            model.onSelection(SelectionIntent.Run(SelectionAction.MARK_WATCHED))
            advanceUntilIdle()

            coVerify(exactly = 0) { userDataRepository.setPlayed(any(), any()) }
            val message = model.uiState.value.userMessage
            message.shouldBeNull()
        }
}
