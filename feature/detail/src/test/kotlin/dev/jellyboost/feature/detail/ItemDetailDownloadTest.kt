package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Download button — solo items, containers (a season or series expanding into episode
 * downloads), and the on-device footprint the metadata line reads once a download finishes.
 *
 * Its own class rather than more of [ItemDetailViewModelTest], which is at detekt's `LargeClass`
 * ceiling — the same split [ItemDetailGroupActionsTest] and [ItemDetailSelectionTest] already make
 * for SyncPlay and batch selection.
 *
 * This file is [DetailDownloadsDelegate]'s coverage, and it is deliberately driven through
 * [ItemDetailViewModel]: the delegate writes into the ViewModel's own state and the screen only
 * ever sees the ViewModel, so the boundary worth holding still is the one these tests exercise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ItemDetailDownloadTest : ItemDetailViewModelFixture() {
    @BeforeEach
    fun setUpEpisodes() {
        coEvery { repository.getEpisodes(any(), any()) } returns AppResult.Success(emptyList())
    }

    // ---- the Download button ---------------------------------------------------------------------

    @Test
    fun `download enqueues an item that is not on the device`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { downloads.enqueue(ITEM_ID) } returns AppResult.Success(Unit)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            coVerify { downloads.enqueue(ITEM_ID) }
            model.uiState.value.userMessage shouldBe UserMessage.DownloadQueued
        }

    @Test
    fun `download on an already-downloaded item asks for confirmation instead of deleting straight away`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)
            downloadStates.value = mapOf(ITEM_ID to DownloadState.Downloaded)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            // A tap that would remove something already on the device is destructive enough to
            // confirm first — nothing is deleted until the dialog is confirmed.
            model.uiState.value.showDeleteConfirmation shouldBe true
            coVerify(exactly = 0) { downloads.deleteAll(any()) }
            coVerify(exactly = 0) { downloads.enqueue(any()) }
        }

    @Test
    fun `confirming the delete-download dialog removes the item and clears the dialog`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)
            downloadStates.value = mapOf(ITEM_ID to DownloadState.Downloaded)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()
            model.confirmDeleteDownload()
            advanceUntilIdle()

            coVerify { downloads.deleteAll(listOf(ITEM_ID)) }
            model.uiState.value.showDeleteConfirmation shouldBe false
            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleted
        }

    @Test
    fun `dismissing the delete-download dialog leaves the download untouched`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            downloadStates.value = mapOf(ITEM_ID to DownloadState.Downloaded)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()
            model.dismissDeleteConfirmation()
            advanceUntilIdle()

            model.uiState.value.showDeleteConfirmation shouldBe false
            coVerify(exactly = 0) { downloads.deleteAll(any()) }
        }

    @Test
    fun `download retries a failed item instead of deleting it`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { downloads.resume(ITEM_ID) } returns AppResult.Success(Unit)
            downloadStates.value = mapOf(ITEM_ID to DownloadState.Failed)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            // Resuming picks up from the bytes already on disk; deleting would throw them away.
            coVerify { downloads.resume(ITEM_ID) }
            coVerify(exactly = 0) { downloads.deleteAll(any()) }
        }

    @Test
    fun `a failed enqueue says so`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { downloads.enqueue(ITEM_ID) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe UserMessage.DownloadFailed
        }

    @Test
    fun `the download state tracks the pipeline`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.downloadState shouldBe DownloadState.NotDownloaded

            downloadStates.value = mapOf(ITEM_ID to DownloadState.Downloading(progress = 0.5f))
            advanceUntilIdle()

            model.uiState.value.downloadState shouldBe DownloadState.Downloading(progress = 0.5f)
        }

    @Test
    fun `download badges reach the season, episode and related cards`() =
        runTest(dispatcher) {
            val related = JellyfinItem(id = "m2", name = "Sicario", type = ItemType.MOVIE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { repository.getSimilarItems(ITEM_ID, any()) } returns AppResult.Success(listOf(related))

            val model = viewModel()
            advanceUntilIdle()
            downloadStates.value = mapOf("m2" to DownloadState.Downloaded)
            advanceUntilIdle()

            model.uiState.value.similar
                .single()
                .downloadState shouldBe DownloadState.Downloaded
        }

    @Test
    fun `download badges reach the next episode and season siblings rows on an episode page`() =
        runTest(dispatcher) {
            val sibling = JellyfinItem(id = EPISODE_1, name = "The Original", type = ItemType.EPISODE)
            val next = JellyfinItem(id = "e3", name = "Dissonance Theory", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(episode)
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns
                AppResult.Success(listOf(sibling, episode, next))
            coEvery { repository.getEpisodes(SERIES_ID, SEASON_ID) } returns
                AppResult.Success(listOf(sibling, episode))

            val model = viewModel()
            advanceUntilIdle()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, "e3" to DownloadState.Downloaded)
            advanceUntilIdle()

            model.uiState.value.seasonEpisodes
                .first { it.id == EPISODE_1 }
                .downloadState shouldBe DownloadState.Downloaded
            model.uiState.value.nextEpisode!!
                .downloadState shouldBe DownloadState.Downloaded
        }

    @Test
    fun `a download state that arrived before the item survives the load`() =
        runTest(dispatcher) {
            downloadStates.value = mapOf(ITEM_ID to DownloadState.Downloaded)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            val model = viewModel()
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()

            model.uiState.value.downloadState shouldBe DownloadState.Downloaded
        }

    @Test
    fun `bytes on disk stay null until the download files report them`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.downloadedBytes
                .shouldBeNull()

            bytesOnDisk.value = 123_456L
            advanceUntilIdle()

            model.uiState.value.downloadedBytes shouldBe 123_456L
        }

    // ---- the Download button on a container ------------------------------------------------------

    @Test
    fun `a season's download button reads its episodes, not a row of its own`() =
        runTest(dispatcher) {
            // A season has no download row — the pipeline expands it into episode downloads — so
            // "is this season downloaded" is a question about its episodes.
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Downloaded)

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.downloadState shouldBe DownloadState.Downloaded
        }

    @Test
    fun `a season halfway through its episodes reports the progress of the whole season`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(
                    EPISODE_1 to DownloadState.Downloaded,
                    EPISODE_2 to DownloadState.Downloading(progress = 0.5f),
                )

            val model = viewModel()
            advanceUntilIdle()

            // One episode done and one half-done is 75 % of the season, not 50 % of whichever file
            // happens to be moving.
            model.uiState.value.downloadState shouldBe DownloadState.Downloading(progress = 0.75f)
        }

    @Test
    fun `a season with only some episodes downloaded still offers to download the rest`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value = mapOf(EPISODE_1 to DownloadState.Downloaded)
            coEvery { downloads.enqueue(ITEM_ID) } returns AppResult.Success(Unit)

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.downloadState shouldBe DownloadState.NotDownloaded

            model.onDownloadClick()
            advanceUntilIdle()

            // Enqueueing the *season* is right: the pipeline expands it and skips the episode that
            // is already on the device.
            coVerify(exactly = 1) { downloads.enqueue(ITEM_ID) }
        }

    @Test
    fun `a season whose episodes failed is enqueued again, not resumed`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Failed)
            coEvery { downloads.enqueue(ITEM_ID) } returns AppResult.Success(Unit)

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.downloadState shouldBe DownloadState.Failed
            model.onDownloadClick()
            advanceUntilIdle()

            // There is no row keyed on the season to put back in the queue; re-enqueueing is what
            // retries the episodes that failed.
            coVerify(exactly = 1) { downloads.enqueue(ITEM_ID) }
            coVerify(exactly = 0) { downloads.resume(any()) }
        }

    @Test
    fun `deleting a downloaded season removes each of its episodes`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Downloaded)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()
            model.uiState.value.showDeleteConfirmation shouldBe true

            model.confirmDeleteDownload()
            advanceUntilIdle()

            // One batch call for the whole season, and the season's own id is not in it: it
            // never had a row, and deleting it would be a no-op round trip.
            coVerify(exactly = 1) { downloads.deleteAll(listOf(EPISODE_1, EPISODE_2)) }
            coVerify(exactly = 1) { downloads.deleteAll(any()) }
            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleted
        }

    @Test
    fun `cancelling a queued season cancels only the episodes that have rows`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value = mapOf(EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.downloadState shouldBe DownloadState.Queued
            model.onDownloadClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.deleteAll(listOf(EPISODE_2)) }
            // Nothing had finished, so this is an ordinary removal — no "kept" message.
            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleted
        }

    @Test
    fun `cancelling a partly-finished season keeps the episodes that already downloaded`() =
        runTest(dispatcher) {
            // Cancel must not run the same delete as Remove and take the finished episodes
            // with it.
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(
                    EPISODE_1 to DownloadState.Downloaded,
                    EPISODE_2 to DownloadState.Downloading(progress = 0.5f),
                )
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            // The finished episode is not in the batch at all — a cancel keeps what landed.
            coVerify(exactly = 1) { downloads.deleteAll(listOf(EPISODE_2)) }
            model.uiState.value.userMessage shouldBe UserMessage.DownloadCancelledKeepingFinished(keptCount = 1)
        }

    @Test
    fun `cancelling a season is one batch call, never one delete per episode`() =
        runTest(dispatcher) {
            // Each single delete stops the download worker and starts it again, and every restart
            // hands the queue the next doomed episode — a server transcode begun for an item the
            // next iteration cancels. One call, one stop, one restart.
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Queued, EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.deleteAll(listOf(EPISODE_1, EPISODE_2)) }
            coVerify(exactly = 1) { downloads.deleteAll(any()) }
            coVerify(exactly = 0) { downloads.delete(any()) }
        }

    @Test
    fun `after a cancel that kept finished episodes the season offers to download the rest`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)
            coEvery { downloads.enqueue(ITEM_ID) } returns AppResult.Success(Unit)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            // The pipeline drops the cancelled row; what is left on the device is the finished
            // episode — the season is partly downloaded, so the button goes back to offering the
            // rest rather than to removing what survived.
            downloadStates.value = mapOf(EPISODE_1 to DownloadState.Downloaded)
            advanceUntilIdle()
            model.uiState.value.downloadState shouldBe DownloadState.NotDownloaded

            model.onDownloadClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.enqueue(ITEM_ID) }
            coVerify(exactly = 0) { downloads.deleteAll(listOf(EPISODE_1)) }
        }

    @Test
    fun `a confirmed delete still removes the finished episodes a cancel would have kept`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            // Not the Cancel path: the dialog's confirm is the "remove everything" affordance and
            // is unfiltered, finished episodes included.
            model.confirmDeleteDownload()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.deleteAll(listOf(EPISODE_1, EPISODE_2)) }
            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleted
        }

    @Test
    fun `one failed episode delete makes the whole season delete report a failure`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Downloaded)
            coEvery { downloads.deleteAll(any()) } returns AppResult.Failure(AppError.Storage())

            val model = viewModel()
            advanceUntilIdle()
            model.confirmDeleteDownload()
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleteFailed
        }

    @Test
    fun `a series page enqueues the whole show`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { downloads.enqueue(ITEM_ID) } returns AppResult.Success(Unit)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.enqueue(ITEM_ID) }
        }
}
