package dev.jellyboost.feature.home

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.HomeSectionType
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.homelayout.DEFAULT_HOME_SECTIONS
import dev.jellyboost.data.homelayout.HomeLayoutRepository
import dev.jellyboost.data.userdata.UserDataChange
import dev.jellyboost.data.userdata.UserDataEventBus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import dev.jellyboost.core.ui.R as CoreUiR

/** Unit tests for [HomeViewModel]'s load, failure and refresh behaviour. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()
    private val eventBus = UserDataEventBus()

    /** The badge source (M7). Most tests do not care, so it emits an empty map and stays quiet. */
    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
        }

    /** The connectivity-change signal (M9); fires only when a test says the server came back. */
    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Online unless a test says otherwise; read by the membership refresh before it fetches. */
    private var online = true
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
            every { isOnline } answers { online }
        }

    /**
     * The user's server-configured row layout. Defaults to what an unconfigured account gets —
     * every row this app draws, in jellyfin-web's order — so tests about anything else are
     * unaffected by it.
     */
    private var sections: List<HomeSectionType> = DEFAULT_HOME_SECTIONS
    private val homeLayout =
        mockk<HomeLayoutRepository> {
            coEvery { getHomeSections() } answers { sections }
        }

    private val movies = LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES)
    private val shows = LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts in the loading state`() =
        runTest(dispatcher) {
            stubEverythingEmpty()

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)

            viewModel.uiState.value.isLoading shouldBe true
        }

    @Test
    fun `loads every home row in jellyfin-web's order`() =
        runTest(dispatcher) {
            val resumeItem = episode("e1", "Trompe L'Oeil")
            val nextUpItem = episode("e2", "Chestnut")
            val movie = movie("m1", "Dune")

            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(resumeItem))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getLatestMedia("lib-movies", any()) } returns AppResult.Success(listOf(movie))
            coEvery { repository.getLatestMedia("lib-shows", any()) } returns AppResult.Success(emptyList())

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.errorMessage.shouldBeNull()
            // `shows` came back with nothing in it, so it gets neither a *Latest* row nor a card:
            // the cards and the shelves are filtered by the same rule.
            state.libraries shouldContainExactly listOf(movies)
            state.resume shouldContainExactly listOf(resumeItem)
            state.nextUp shouldContainExactly listOf(nextUpItem)
            // Empty "Latest" sections are dropped, exactly as jellyfin-web omits empty shelves.
            state.latest.map { it.library } shouldContainExactly listOf(movies)
            state.latest.single().items shouldContainExactly listOf(movie)
        }

    @Test
    fun `keeps a library card whose latest row only failed`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(emptyList())
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(emptyList())
            coEvery { repository.getLatestMedia("lib-movies", any()) } returns
                AppResult.Success(listOf(movie("m1", "Dune")))
            coEvery { repository.getLatestMedia("lib-shows", any()) } returns
                AppResult.Failure(AppError.Server(503))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // One flaky request must not make a library disappear from the home screen — only a
            // library that *answered* with nothing loses its card.
            state.libraries shouldContainExactly listOf(movies, shows)
            state.latest.map { it.library } shouldContainExactly listOf(movies)
        }

    @Test
    fun `requests one latest row per library`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))

            HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getLatestMedia("lib-movies", any()) }
            coVerify(exactly = 1) { repository.getLatestMedia("lib-shows", any()) }
        }

    @Test
    fun `emits loading then loaded`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            stubLibrariesWithContent(movies)

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)

            viewModel.uiState.test {
                awaitItem().isLoading shouldBe true
                advanceUntilIdle()
                val loaded = awaitItem()
                loaded.isLoading shouldBe false
                loaded.libraries shouldContainExactly listOf(movies)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `surfaces an error when the libraries call fails`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Network())

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.errorMessage shouldBe UiText.res(CoreUiR.string.error_network)
            state.libraries.shouldBeEmpty()
        }

    @Test
    fun `does not call the row endpoints when the libraries call fails`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Unauthorized())

            HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getResumeItems(any()) }
            coVerify(exactly = 0) { repository.getNextUp(any()) }
        }

    @Test
    fun `leaves a failing row empty instead of blanking the screen`() =
        runTest(dispatcher) {
            val nextUpItem = episode("e2", "Chestnut")
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Failure(AppError.Server(503))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(emptyList())

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.errorMessage.shouldBeNull()
            state.resume.shouldBeEmpty()
            state.nextUp shouldContainExactly listOf(nextUpItem)
        }

    @Test
    fun `reports an empty state when the server has nothing to show`() =
        runTest(dispatcher) {
            stubEverythingEmpty()

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            viewModel.uiState.value.isEmpty shouldBe true
        }

    @Test
    fun `refresh re-fetches every row and clears a previous error`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Network())

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            viewModel.uiState.value.errorMessage shouldBe UiText.res(CoreUiR.string.error_network)

            stubEverythingEmpty()
            stubLibrariesWithContent(movies)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.errorMessage.shouldBeNull()
            state.isRefreshing shouldBe false
            state.libraries shouldContainExactly listOf(movies)
            coVerify(exactly = 2) { repository.getUserViews() }
        }

    // ---- M9: refresh when connectivity changes --------------------------------------------------

    @Test
    fun `re-fetches the rows when the server becomes reachable again`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            // The initial load, and nothing else: an app that starts online must not fetch twice.
            coVerify(exactly = 1) { repository.getUserViews() }

            stubLibrariesWithContent(movies)
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getUserViews() }
            viewModel.uiState.value.libraries shouldContainExactly listOf(movies)
        }

    @Test
    fun `drops the server's rows when the connection is lost`() =
        runTest(dispatcher) {
            val streamed = movie("m1", "Dune")
            val downloaded = movie("m2", "Arrival")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(streamed))
            coEvery { repository.getLatestMedia("lib-movies", any()) } returns
                AppResult.Success(listOf(streamed))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            viewModel.uiState.value.resume shouldContainExactly listOf(streamed)

            // Offline mode: the repository now answers from Room, with downloads only. Without a
            // reload the screen would keep offering media it can no longer play.
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(downloaded))
            coEvery { repository.getLatestMedia("lib-movies", any()) } returns
                AppResult.Success(listOf(downloaded))
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.resume shouldContainExactly listOf(downloaded)
            state.latest.single().items shouldContainExactly listOf(downloaded)
        }

    @Test
    fun `hides a library card with nothing behind it when the connection is lost`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            stubLibrariesWithContent(movies, shows)

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            viewModel.uiState.value.libraries shouldContainExactly listOf(movies, shows)

            // Offline `getUserViews` still returns every cached library view; only the one with
            // downloads behind it has anything to open.
            coEvery { repository.getLatestMedia("lib-shows", any()) } returns AppResult.Success(emptyList())
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            viewModel.uiState.value.libraries shouldContainExactly listOf(movies)
        }

    // ---- M4: user-data event bus --------------------------------------------------------------

    @Test
    fun `patches a loaded row when user data changes elsewhere, without refetching`() =
        runTest(dispatcher) {
            val resumeItem = episode("e1", "Trompe L'Oeil")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(shows))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(resumeItem))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            viewModel.uiState.value.resume
                .single()
                .userData.isFavorite shouldBe false

            eventBus.emit(UserDataChange("e1", UserData(isFavorite = true)))
            advanceUntilIdle()

            viewModel.uiState.value.resume
                .single()
                .userData.isFavorite shouldBe true
            // The whole point: a change that cannot move an item between rows costs no round-trip
            // at all — not even after the membership-refresh window has long passed.
            coVerify(exactly = 1) { repository.getUserViews() }
            coVerify(exactly = 1) { repository.getResumeItems(any()) }
            coVerify(exactly = 1) { repository.getNextUp(any()) }
        }

    @Test
    fun `a position report does not refetch`() =
        runTest(dispatcher) {
            val resumeItem = episode("e1", "Trompe L'Oeil")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(shows))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(resumeItem))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // `PlaybackReporter` writes one of these every five seconds; a refetch per tick would
            // be a poll for the length of the film.
            repeat(3) { tick ->
                eventBus.emit(UserDataChange("e1", UserData(playbackPositionTicks = (tick + 1) * 100L)))
                advanceUntilIdle()
            }

            viewModel.uiState.value.resume
                .single()
                .userData.playbackPositionTicks shouldBe 300L
            coVerify(exactly = 1) { repository.getResumeItems(any()) }
            coVerify(exactly = 1) { repository.getNextUp(any()) }
        }

    @Test
    fun `patches the same item across every row it appears in`() =
        runTest(dispatcher) {
            val nextUpItem = episode("e2", "Chestnut")
            val movie = movie("m1", "Dune")
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            eventBus.emit(UserDataChange("e2", UserData(isFavorite = true)))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.resume
                .single()
                .userData.isFavorite shouldBe true
            state.nextUp
                .single()
                .userData.isFavorite shouldBe true
            // An untouched row keeps its identity so Compose can skip it entirely.
            state.latest
                .single()
                .items
                .single()
                .userData.isFavorite shouldBe false
        }

    @Test
    fun `ignores a change for an item no row is showing`() =
        runTest(dispatcher) {
            val movie = movie("m1", "Dune")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            val before = viewModel.uiState.value

            eventBus.emit(UserDataChange("somewhere-else", UserData(played = true)))
            advanceUntilIdle()

            viewModel.uiState.value shouldBe before
        }

    // ---- Watched state moves items between rows ------------------------------------------------

    @Test
    fun `marking a resume item watched drops it from continue watching in the same frame`() =
        runTest(dispatcher) {
            val movie = movie("m1", "Dune")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            eventBus.emit(UserDataChange("m1", UserData(played = true)))
            runCurrent()

            // Instant and request-free: *Continue watching* holds unfinished items, so a played
            // item does not belong in it — no server round-trip is needed to know that.
            viewModel.uiState.value.resume
                .shouldBeEmpty()
            coVerify(exactly = 1) { repository.getResumeItems(any()) }
        }

    @Test
    fun `advances next up to the following episode after a watched toggle`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(shows))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(episode("e1", "The Original")))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // Which episode is "next" is the server's answer, not something a patch can synthesise.
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(episode("e2", "Chestnut")))
            eventBus.emit(UserDataChange("e1", UserData(played = true)))
            advanceUntilIdle()

            viewModel.uiState.value.nextUp
                .map { it.id }
                .shouldContainExactly(listOf("e2"))
            coVerify(exactly = 2) { repository.getNextUp(any()) }
            coVerify(exactly = 2) { repository.getResumeItems(any()) }
            // The rest of the screen is left alone: what is *recently added* does not depend on
            // what has been watched, and the libraries call is not repeated either.
            coVerify(exactly = 1) { repository.getUserViews() }
            coVerify(exactly = 1) { repository.getLatestMedia(any(), any()) }
        }

    @Test
    fun `refreshes the rows when a series no row shows is marked watched`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(shows))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(episode("e1", "The Original")))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // *Mark watched* on a series or season page publishes the container's id, which no
            // episode card matches — the patch alone can never fix these rows.
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(emptyList())
            eventBus.emit(UserDataChange("series-1", UserData(played = true)))
            advanceUntilIdle()

            viewModel.uiState.value.nextUp
                .shouldBeEmpty()
            coVerify(exactly = 2) { repository.getNextUp(any()) }
        }

    @Test
    fun `collapses a burst of watched toggles into a single refresh`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(shows))

            HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // Marking a season watched is one write per episode.
            repeat(TOGGLE_BURST) { index ->
                eventBus.emit(UserDataChange("e$index", UserData(played = true)))
                advanceTimeBy(WITHIN_DEBOUNCE_MS)
            }
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getResumeItems(any()) }
            coVerify(exactly = 2) { repository.getNextUp(any()) }
        }

    @Test
    fun `does not refresh the rows while offline`() =
        runTest(dispatcher) {
            online = false
            val movie = movie("m1", "Dune")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            eventBus.emit(UserDataChange("m1", UserData(played = true)))
            advanceUntilIdle()

            // The rows are the downloads Room already answered with, and the write has nowhere to
            // have been adopted — but the item still leaves the row.
            viewModel.uiState.value.resume
                .shouldBeEmpty()
            coVerify(exactly = 1) { repository.getResumeItems(any()) }
            coVerify(exactly = 1) { repository.getNextUp(any()) }
        }

    @Test
    fun `keeps the local watched state when the refetched row is still stale`() =
        runTest(dispatcher) {
            val movie = movie("m1", "Dune")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // The server still answers with the pre-toggle row: the push is slow, or it failed and
            // is queued for `UserDataSyncWorker`. A local write that is still pending outranks it.
            eventBus.emit(UserDataChange("m1", UserData(played = true)))
            advanceUntilIdle()

            viewModel.uiState.value.resume
                .shouldBeEmpty()
            coVerify(exactly = 2) { repository.getResumeItems(any()) }
        }

    @Test
    fun `a pull-to-refresh takes the server's answer over an earlier local toggle`() =
        runTest(dispatcher) {
            val movie = movie("m1", "Dune")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            eventBus.emit(UserDataChange("m1", UserData(played = true)))
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            // Asking for a reload means asking the server; the local overrides stop applying, and
            // anything genuinely unsynced comes back on the bus once `UserDataSyncer` resolves it.
            viewModel.uiState.value.resume
                .map { it.id }
                .shouldContainExactly(listOf("m1"))
        }

    @Test
    fun `leaves the rows untouched when the silent refresh fails`() =
        runTest(dispatcher) {
            val resumeItem = movie("m1", "Dune")
            val nextUpItem = episode("e1", "The Original")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(shows))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(resumeItem))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(nextUpItem))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            coEvery { repository.getResumeItems(any()) } returns AppResult.Failure(AppError.Server(503))
            coEvery { repository.getNextUp(any()) } returns AppResult.Failure(AppError.Network())
            eventBus.emit(UserDataChange("series-1", UserData(played = true)))
            advanceUntilIdle()

            // Silent means silent: the user toggled something on another screen, so a failure here
            // must not empty a shelf or raise an error the user did not ask for.
            val state = viewModel.uiState.value
            state.resume shouldContainExactly listOf(resumeItem)
            state.nextUp shouldContainExactly listOf(nextUpItem)
            state.errorMessage.shouldBeNull()
            state.isRefreshing shouldBe false
        }

    // ---- M7: download badges -------------------------------------------------------------------

    @Test
    fun `download state reaches every card that shows the item`() =
        runTest(dispatcher) {
            val movie = movie("m1", "Dune")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(movie))
            coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            downloadStates.value = mapOf("m1" to DownloadState.Downloaded)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.resume.single().downloadState shouldBe DownloadState.Downloaded
            state.latest
                .single()
                .items
                .single()
                .downloadState shouldBe DownloadState.Downloaded
        }

    @Test
    fun `a download state that arrives before the rows survives the load`() =
        runTest(dispatcher) {
            // `observeStates()` is distinct-until-changed, so it does not re-emit just because the
            // screen refetched; the badge would vanish on every refresh if the state were not held.
            downloadStates.value = mapOf("m1" to DownloadState.Downloaded)
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getLatestMedia(any(), any()) } returns
                AppResult.Success(listOf(movie("m1", "Dune")))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.uiState.value.latest
                .single()
                .items
                .single()
                .downloadState shouldBe DownloadState.Downloaded
        }

    @Test
    fun `an item with no download row keeps the not-downloaded badge`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(movie("m1", "Dune")))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()
            downloadStates.value = mapOf("somewhere-else" to DownloadState.Downloaded)
            advanceUntilIdle()

            viewModel.uiState.value.resume
                .single()
                .downloadState shouldBe DownloadState.NotDownloaded
        }

    // ---- Server-configured section layout -------------------------------------------------------

    @Test
    fun `the resolved layout reaches the state in the order the server gave it`() =
        runTest(dispatcher) {
            sections =
                listOf(
                    HomeSectionType.LATEST_MEDIA,
                    HomeSectionType.NEXT_UP,
                    HomeSectionType.SMALL_LIBRARY_TILES,
                    HomeSectionType.RESUME,
                )
            stubEverythingEmpty()
            stubLibrariesWithContent(movies)

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // The screen renders `sections`, so this list *is* the row order.
            viewModel.uiState.value.sections shouldContainExactly sections
        }

    @Test
    fun `a hidden row is neither fetched nor shown`() =
        runTest(dispatcher) {
            sections = listOf(HomeSectionType.SMALL_LIBRARY_TILES, HomeSectionType.RESUME)
            stubEverythingEmpty()
            stubLibrariesWithContent(movies)
            coEvery { repository.getResumeItems(any()) } returns
                AppResult.Success(listOf(movie("m1", "Dune")))
            coEvery { repository.getNextUp(any()) } returns
                AppResult.Success(listOf(episode("e1", "The Original")))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // Hiding *Next up* in jellyfin-web costs the request as well as the row.
            coVerify(exactly = 0) { repository.getNextUp(any()) }
            val state = viewModel.uiState.value
            state.nextUp.shouldBeEmpty()
            state.sections shouldNotContain HomeSectionType.NEXT_UP
            state.resume shouldContainExactly listOf(movie("m1", "Dune"))
        }

    @Test
    fun `a layout without latest media still shows the libraries row`() =
        runTest(dispatcher) {
            sections = listOf(HomeSectionType.SMALL_LIBRARY_TILES)
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // Nothing asks what is behind each library, so nothing filters the cards either.
            coVerify(exactly = 0) { repository.getLatestMedia(any(), any()) }
            viewModel.uiState.value.libraries shouldContainExactly listOf(movies, shows)
        }

    @Test
    fun `a layout with no library-backed row skips the libraries call entirely`() =
        runTest(dispatcher) {
            sections = listOf(HomeSectionType.RESUME, HomeSectionType.NEXT_UP)
            stubEverythingEmpty()
            coEvery { repository.getResumeItems(any()) } returns
                AppResult.Success(listOf(movie("m1", "Dune")))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getUserViews() }
            val state = viewModel.uiState.value
            state.errorMessage.shouldBeNull()
            state.resume shouldContainExactly listOf(movie("m1", "Dune"))
        }

    @Test
    fun `the layout is re-resolved on every refresh`() =
        runTest(dispatcher) {
            sections = DEFAULT_HOME_SECTIONS
            stubEverythingEmpty()
            stubLibrariesWithContent(movies)

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            // Changing Settings → Home in jellyfin-web and pulling to refresh is the whole
            // freshness story — there is no polling.
            sections = listOf(HomeSectionType.RESUME)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.uiState.value.sections shouldContainExactly listOf(HomeSectionType.RESUME)
            coVerify(exactly = 2) { homeLayout.getHomeSections() }
        }

    @Test
    fun `the membership refresh skips a hidden row`() =
        runTest(dispatcher) {
            sections = listOf(HomeSectionType.RESUME)
            stubEverythingEmpty()
            coEvery { repository.getResumeItems(any()) } returns
                AppResult.Success(listOf(movie("m1", "Dune")))

            val viewModel = HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            eventBus.emit(UserDataChange("series-1", UserData(played = true)))
            advanceUntilIdle()

            // *Continue watching* is on screen and is re-fetched; *Next up* is not on screen at
            // all, so its membership is nobody's question.
            coVerify(exactly = 2) { repository.getResumeItems(any()) }
            coVerify(exactly = 0) { repository.getNextUp(any()) }
            viewModel.uiState.value.resume shouldContainExactly listOf(movie("m1", "Dune"))
        }

    @Test
    fun `a layout showing neither unfinished row never refreshes membership`() =
        runTest(dispatcher) {
            sections = listOf(HomeSectionType.LATEST_MEDIA)
            stubEverythingEmpty()
            stubLibrariesWithContent(movies)

            HomeViewModel(repository, homeLayout, eventBus, downloads, connectivityRefresher)
            advanceUntilIdle()

            eventBus.emit(UserDataChange("series-1", UserData(played = true)))
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getResumeItems(any()) }
            coVerify(exactly = 0) { repository.getNextUp(any()) }
        }

    /**
     * Makes [libraries] the only libraries the server knows about, each with one item in it — enough
     * for every one of them to keep its *My Media* card, which an empty library no longer gets.
     */
    private fun stubLibrariesWithContent(vararg libraries: LibraryView) {
        coEvery { repository.getUserViews() } returns AppResult.Success(libraries.toList())
        libraries.forEach { library ->
            coEvery { repository.getLatestMedia(library.id, any()) } returns
                AppResult.Success(listOf(movie("m-${library.id}", "Dune")))
        }
    }

    private fun stubEverythingEmpty() {
        coEvery { repository.getUserViews() } returns AppResult.Success(emptyList())
        coEvery { repository.getResumeItems(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getNextUp(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(emptyList())
    }

    private fun episode(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.EPISODE, seriesName = "Westworld")

    private fun movie(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MOVIE)

    private companion object {
        /** Episodes toggled in one go — "mark season watched" is one write per episode. */
        const val TOGGLE_BURST = 5

        /** Spacing between those toggles: short enough that the debounce window never elapses. */
        const val WITHIN_DEBOUNCE_MS = 500L
    }
}
