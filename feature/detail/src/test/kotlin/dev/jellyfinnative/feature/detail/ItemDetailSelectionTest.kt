package dev.jellyfinnative.feature.detail

import androidx.lifecycle.SavedStateHandle
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.core.common.selection.BatchOutcome
import dev.jellyfinnative.core.common.selection.BatchReport
import dev.jellyfinnative.core.common.selection.SelectionAction
import dev.jellyfinnative.core.common.selection.SelectionIntent
import dev.jellyfinnative.data.ConnectivityRefresher
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.userdata.UserDataChange
import dev.jellyfinnative.data.userdata.UserDataRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for batch selection over the season page's episode list
 * (docs/features/batch-selection.md).
 *
 * A file of its own rather than more of [ItemDetailViewModelTest]: that class already covers the
 * load shapes, the toggles and the Download button, and folding a whole interaction mode into it
 * puts it past detekt's `LargeClass` threshold. The fixture is the same season page — two episodes,
 * loaded from their series — every test here starts from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()
    private val userDataRepository = mockk<UserDataRepository>()
    private val changes =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The badge source (M7); emits an empty map unless a test says otherwise. */
    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
            every { observeBytesOnDisk(any()) } returns MutableStateFlow<Long?>(null)
        }

    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
        }

    private val season =
        JellyfinItem(id = ITEM_ID, name = "Season 1", type = ItemType.SEASON, seriesId = SERIES_ID)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { userDataRepository.changes } returns changes
        coEvery { repository.getSeasons(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getNextUpForSeries(any()) } returns AppResult.Success(null)
        coEvery { repository.getSimilarItems(any(), any()) } returns AppResult.Success(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

            // Mode is derived from emptiness, so there is no way to sit in it with nothing selected.
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

            // The second episode is gone from the server's answer on the refresh.
            coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns
                AppResult.Success(
                    listOf(JellyfinItem(id = EPISODE_1, name = "The Original", type = ItemType.EPISODE)),
                )
            model.refresh()
            advanceUntilIdle()

            // A refresh here is a connectivity edge, not something the user asked for, so the
            // selection survives it — but never as an id with no row on the screen.
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

            // The second episode is written even though the first failed.
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

            // Re-enqueueing a finished single item would reset its row to QUEUED and download it
            // again — `DownloadEnqueuer` only skips on the container path.
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

            // Retrying a failure is what a second Download tap means; a queued row is left alone.
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

            // Before `advanceUntilIdle`: the bar is already gone, the writes have not run yet.
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

    /** The season page as the user reaches it: two episodes, loaded from its series. */
    private fun givenSeasonWithEpisodes() {
        coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(season)
        coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns
            AppResult.Success(
                listOf(
                    JellyfinItem(id = EPISODE_1, name = "The Original", type = ItemType.EPISODE),
                    JellyfinItem(id = EPISODE_2, name = "Chestnut", type = ItemType.EPISODE),
                ),
            )
    }

    private fun viewModel() =
        ItemDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            downloads = downloads,
            connectivityRefresher = connectivityRefresher,
            // No group: batch selection has nothing to do with SyncPlay, and a relaxed double keeps
            // it that way (M11 Phase 4).
            syncPlaySession =
                mockk(relaxed = true) {
                    every { activeGroup } returns MutableStateFlow(null)
                },
            savedStateHandle = SavedStateHandle(mapOf(ItemDetailViewModel.ARG_ITEM_ID to ITEM_ID)),
        )

    private companion object {
        const val ITEM_ID = "item-1"
        const val SERIES_ID = "series-1"
        const val EPISODE_1 = "episode-1"
        const val EPISODE_2 = "episode-2"
    }
}
