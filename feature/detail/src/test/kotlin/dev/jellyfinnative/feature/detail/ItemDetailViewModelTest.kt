package dev.jellyfinnative.feature.detail

import androidx.lifecycle.SavedStateHandle
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.data.ConnectivityRefresher
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.userdata.UserDataChange
import dev.jellyfinnative.data.userdata.UserDataRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

/** Unit tests for [ItemDetailViewModel] — load shapes, toggles and the event-bus patch. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailViewModelTest {
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
        }

    /** The connectivity-change signal (M9); fires only when a test says the server came back. */
    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
        }

    private val movie =
        JellyfinItem(id = ITEM_ID, name = "Arrival", type = ItemType.MOVIE, productionYear = 2016)
    private val series = JellyfinItem(id = ITEM_ID, name = "Westworld", type = ItemType.SERIES)
    private val season =
        JellyfinItem(id = ITEM_ID, name = "Season 1", type = ItemType.SEASON, seriesId = SERIES_ID)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { userDataRepository.changes } returns changes
        coEvery { repository.getSeasons(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getEpisodes(any(), any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getNextUpForSeries(any()) } returns AppResult.Success(null)
        coEvery { repository.getSimilarItems(any(), any()) } returns AppResult.Success(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- loading ------------------------------------------------------------------------------

    @Test
    fun `starts in the loading state`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            viewModel().uiState.value.isLoading shouldBe true
        }

    @Test
    fun `a movie loads its item and a More like this row, nothing else`() =
        runTest(dispatcher) {
            val related = JellyfinItem(id = "m2", name = "Sicario", type = ItemType.MOVIE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { repository.getSimilarItems(ITEM_ID, any()) } returns AppResult.Success(listOf(related))

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.isLoaded shouldBe true
            state.item!!.name shouldBe "Arrival"
            state.similar shouldContainExactly listOf(related)
            state.seasons.shouldBeEmpty()
            state.episodes.shouldBeEmpty()
            coVerify(exactly = 0) { repository.getSeasons(any()) }
            coVerify(exactly = 0) { repository.getEpisodes(any(), any()) }
        }

    @Test
    fun `a series loads its seasons and next up`() =
        runTest(dispatcher) {
            val seasonItem = JellyfinItem(id = "s1", name = "Season 1", type = ItemType.SEASON)
            val next = JellyfinItem(id = "e1", name = "Chestnut", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getSeasons(ITEM_ID) } returns AppResult.Success(listOf(seasonItem))
            coEvery { repository.getNextUpForSeries(ITEM_ID) } returns AppResult.Success(next)

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.seasons shouldContainExactly listOf(seasonItem)
            state.nextUp shouldBe next
            state.episodes.shouldBeEmpty()
        }

    @Test
    fun `a season loads its episodes, scoped to its series`() =
        runTest(dispatcher) {
            val episode = JellyfinItem(id = "e1", name = "The Original", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(season)
            coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns AppResult.Success(listOf(episode))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.episodes shouldContainExactly listOf(episode)
            coVerify(exactly = 1) { repository.getEpisodes(SERIES_ID, ITEM_ID) }
            // A season is browsed through its series, so "more like this" would be noise.
            coVerify(exactly = 0) { repository.getSimilarItems(any(), any()) }
        }

    @Test
    fun `surfaces an error when the item itself cannot be loaded`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.isLoading shouldBe false
            state.errorMessage!! shouldContain "server"
            state.item.shouldBeNull()
        }

    @Test
    fun `leaves a failing related row empty instead of blanking the page`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getSeasons(ITEM_ID) } returns AppResult.Failure(AppError.Server(503))

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.errorMessage.shouldBeNull()
            state.item.shouldNotBeNull()
            state.seasons.shouldBeEmpty()
        }

    @Test
    fun `refresh re-fetches the item and clears a previous error`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.errorMessage!! shouldContain "server"

            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            model.refresh()
            advanceUntilIdle()

            model.uiState.value.errorMessage
                .shouldBeNull()
            coVerify(exactly = 2) { repository.getItem(ITEM_ID) }
        }

    // ---- user data ----------------------------------------------------------------------------

    @Test
    fun `mark watched toggles from the item's current state`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed(ITEM_ID, true) }
        }

    @Test
    fun `mark watched on an already watched item unmarks it`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(userData = UserData(played = true)))
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed(ITEM_ID, false) }
        }

    @Test
    fun `favourite toggles from the item's current state`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setFavorite(any(), any()) } returns AppResult.Success(UserData())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleFavorite()
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setFavorite(ITEM_ID, true) }
        }

    @Test
    fun `reflects a toggle optimistically from the event bus, with no refetch`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData(played = true))

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            // The local write publishes on the bus; that is what flips the button.
            changes.emit(UserDataChange(ITEM_ID, UserData(played = true)))
            advanceUntilIdle()

            model.uiState.value.item!!
                .userData.played shouldBe true
            coVerify(exactly = 1) { repository.getItem(ITEM_ID) }
        }

    @Test
    fun `patches a season in the seasons row when its user data changes`() =
        runTest(dispatcher) {
            val seasonItem = JellyfinItem(id = "s1", name = "Season 1", type = ItemType.SEASON)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getSeasons(ITEM_ID) } returns AppResult.Success(listOf(seasonItem))

            val model = viewModel()
            advanceUntilIdle()

            changes.emit(UserDataChange("s1", UserData(played = true)))
            advanceUntilIdle()

            model.uiState.value.seasons
                .single()
                .userData.played shouldBe true
            model.uiState.value.item!!
                .userData.played shouldBe false
        }

    @Test
    fun `raises a message when a toggle cannot even be written locally`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setFavorite(any(), any()) } returns
                AppResult.Failure(AppError.Storage())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleFavorite()
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe UserMessage.UserDataWriteFailed
        }

    @Test
    fun `a toggle before the item has loaded does nothing`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            coVerify(exactly = 0) { userDataRepository.setPlayed(any(), any()) }
        }

    // ---- what Play actually plays ---------------------------------------------------------------

    @Test
    fun `a movie plays itself, from the start when it was never watched`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            val model = viewModel()
            advanceUntilIdle()

            val target = model.uiState.value.playTarget
            target!!.id shouldBe ITEM_ID
            playbackStartTicks(target) shouldBe 0L
        }

    @Test
    fun `a partly watched movie resumes where it was left`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(userData = UserData(playbackPositionTicks = 600L)))

            val model = viewModel()
            advanceUntilIdle()

            playbackStartTicks(model.uiState.value.playTarget!!) shouldBe 600L
        }

    @Test
    fun `a position written while offline turns the button into Resume, with no refetch`() =
        runTest(dispatcher) {
            // The offline half of the M8 chain: the player writes the position locally on every
            // tick and publishes it, and this screen is where the user sees it — a downloaded film
            // watched in airplane mode offers Resume at the right place with no server involved.
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            val model = viewModel()
            advanceUntilIdle()

            changes.emit(UserDataChange(ITEM_ID, UserData(playbackPositionTicks = 36_000_000_000L)))
            advanceUntilIdle()

            playbackStartTicks(model.uiState.value.playTarget!!) shouldBe 36_000_000_000L
            coVerify(exactly = 1) { repository.getItem(ITEM_ID) }
        }

    @Test
    fun `a watched movie starts again from the beginning`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(userData = UserData(played = true, playbackPositionTicks = 600L)))

            val model = viewModel()
            advanceUntilIdle()

            playbackStartTicks(model.uiState.value.playTarget!!) shouldBe 0L
        }

    @Test
    fun `a series plays the episode the server calls next up`() =
        runTest(dispatcher) {
            val next = JellyfinItem(id = "e5", name = "Trompe L'Oeil", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getNextUpForSeries(ITEM_ID) } returns AppResult.Success(next)

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.playTarget shouldBe next
        }

    @Test
    fun `a season plays its first unwatched episode`() =
        runTest(dispatcher) {
            val watched =
                JellyfinItem(
                    id = "e1",
                    name = "The Original",
                    type = ItemType.EPISODE,
                    userData = UserData(played = true),
                )
            val next = JellyfinItem(id = "e2", name = "Chestnut", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(season)
            coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns
                AppResult.Success(listOf(watched, next))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.playTarget shouldBe next
        }

    // ---- M7: the Download button ----------------------------------------------------------------

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
            coEvery { downloads.delete(ITEM_ID) } returns AppResult.Success(0L)
            downloadStates.value = mapOf(ITEM_ID to DownloadState.Downloaded)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            // A tap that would remove something already on the device is destructive enough to
            // confirm first (docs/POLISH.md) — nothing is deleted until the dialog is confirmed.
            model.uiState.value.showDeleteConfirmation shouldBe true
            coVerify(exactly = 0) { downloads.delete(any()) }
            coVerify(exactly = 0) { downloads.enqueue(any()) }
        }

    @Test
    fun `confirming the delete-download dialog removes the item and clears the dialog`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { downloads.delete(ITEM_ID) } returns AppResult.Success(0L)
            downloadStates.value = mapOf(ITEM_ID to DownloadState.Downloaded)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()
            model.confirmDeleteDownload()
            advanceUntilIdle()

            coVerify { downloads.delete(ITEM_ID) }
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
            coVerify(exactly = 0) { downloads.delete(any()) }
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
            coVerify(exactly = 0) { downloads.delete(any()) }
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

    // ---- the Download button on a container (docs/POLISH.md: downloading a season failed) --------

    @Test
    fun `a season's download button reads its episodes, not a row of its own`() =
        runTest(dispatcher) {
            // A season has no download row — the pipeline expands it into episode downloads — so
            // "is this season downloaded" is a question about its episodes (DECISIONS.md,
            // 2026-07-29).
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
            coEvery { downloads.delete(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()
            model.uiState.value.showDeleteConfirmation shouldBe true

            model.confirmDeleteDownload()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.delete(EPISODE_1) }
            coVerify(exactly = 1) { downloads.delete(EPISODE_2) }
            // The season itself never had a row; deleting it would be a no-op round trip.
            coVerify(exactly = 0) { downloads.delete(ITEM_ID) }
            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleted
        }

    @Test
    fun `cancelling a queued season cancels only the episodes that have rows`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value = mapOf(EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.delete(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.downloadState shouldBe DownloadState.Queued
            model.onDownloadClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.delete(EPISODE_2) }
            coVerify(exactly = 0) { downloads.delete(EPISODE_1) }
            // Nothing had finished, so this is an ordinary removal — no "kept" message.
            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleted
        }

    @Test
    fun `cancelling a partly-finished season keeps the episodes that already downloaded`() =
        runTest(dispatcher) {
            // The bug this covers: Cancel used to run the same delete as Remove and take the
            // finished episodes with it (DECISIONS.md, 2026-07-29).
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(
                    EPISODE_1 to DownloadState.Downloaded,
                    EPISODE_2 to DownloadState.Downloading(progress = 0.5f),
                )
            coEvery { downloads.delete(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.delete(EPISODE_2) }
            coVerify(exactly = 0) { downloads.delete(EPISODE_1) }
            model.uiState.value.userMessage shouldBe UserMessage.DownloadCancelledKeepingFinished(keptCount = 1)
        }

    @Test
    fun `after a cancel that kept finished episodes the season offers to download the rest`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.delete(any()) } returns AppResult.Success(0L)
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
            coVerify(exactly = 0) { downloads.delete(EPISODE_1) }
        }

    @Test
    fun `a confirmed delete still removes the finished episodes a cancel would have kept`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Queued)
            coEvery { downloads.delete(any()) } returns AppResult.Success(0L)

            val model = viewModel()
            advanceUntilIdle()
            // Not the Cancel path: the dialog's confirm is the "remove everything" affordance and
            // is unfiltered, finished episodes included.
            model.confirmDeleteDownload()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.delete(EPISODE_1) }
            coVerify(exactly = 1) { downloads.delete(EPISODE_2) }
            model.uiState.value.userMessage shouldBe UserMessage.DownloadDeleted
        }

    @Test
    fun `one failed episode delete makes the whole season delete report a failure`() =
        runTest(dispatcher) {
            givenSeasonWithEpisodes()
            downloadStates.value =
                mapOf(EPISODE_1 to DownloadState.Downloaded, EPISODE_2 to DownloadState.Downloaded)
            coEvery { downloads.delete(EPISODE_1) } returns AppResult.Success(0L)
            coEvery { downloads.delete(EPISODE_2) } returns AppResult.Failure(AppError.Storage())

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

    // ---- M9: refresh when connectivity changes ----------------------------------------------------------------

    @Test
    fun `re-fetches the item it is showing when the server becomes reachable again`() =
        runTest(dispatcher) {
            // Offline this page is a cached row, or a placeholder for something not downloaded.
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(name = "Arrival", available = false))
            val model = viewModel()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.getItem(ITEM_ID) }

            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getItem(ITEM_ID) }
            model.uiState.value.item!!
                .available shouldBe true
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
            savedStateHandle = SavedStateHandle(mapOf(ItemDetailViewModel.ARG_ITEM_ID to ITEM_ID)),
        )

    private companion object {
        const val ITEM_ID = "item-1"
        const val SERIES_ID = "series-1"
        const val EPISODE_1 = "episode-1"
        const val EPISODE_2 = "episode-2"
    }
}
