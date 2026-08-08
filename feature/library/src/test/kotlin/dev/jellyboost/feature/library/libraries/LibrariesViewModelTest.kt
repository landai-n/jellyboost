package dev.jellyboost.feature.library.libraries

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.feature.library.MainDispatcherExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** Unit tests for [LibrariesViewModel]'s load, failure and refresh behaviour. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrariesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()

    /** The connectivity-change signal (M9); fires only when a test says the server came back. */
    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
        }

    private val movies = LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES)
    private val shows = LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS)

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(dispatcher)

    @Test
    fun `starts in the loading state`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Success(emptyList())

            val viewModel = LibrariesViewModel(repository, connectivityRefresher)

            viewModel.uiState.value.isLoading shouldBe true
        }

    @Test
    fun `loads the user's libraries`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))

            val viewModel = LibrariesViewModel(repository, connectivityRefresher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.error.shouldBeNull()
            state.libraries shouldContainExactly listOf(movies, shows)
        }

    @Test
    fun `emits loading then loaded`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))

            val viewModel = LibrariesViewModel(repository, connectivityRefresher)

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
    fun `surfaces an error when the call fails`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Network())

            val viewModel = LibrariesViewModel(repository, connectivityRefresher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.error.shouldBeInstanceOf<AppError.Network>()
            state.libraries.shouldBeEmpty()
        }

    @Test
    fun `reports an empty state when the server has no libraries`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Success(emptyList())

            val viewModel = LibrariesViewModel(repository, connectivityRefresher)
            advanceUntilIdle()

            viewModel.uiState.value.isEmpty shouldBe true
        }

    @Test
    fun `refresh re-fetches and clears a previous error`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Network())

            val viewModel = LibrariesViewModel(repository, connectivityRefresher)
            advanceUntilIdle()
            val failed = viewModel.uiState.value
            failed.error.shouldBeInstanceOf<AppError.Network>()

            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.error.shouldBeNull()
            state.libraries shouldContainExactly listOf(movies)
            coVerify(exactly = 2) { repository.getUserViews() }
        }

    // ---- M9: refresh when connectivity changes ---------------------------------------------------------------

    @Test
    fun `re-fetches the libraries when the server becomes reachable again`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Success(emptyList())

            val viewModel = LibrariesViewModel(repository, connectivityRefresher)
            advanceUntilIdle()
            // The initial load, and nothing else: an app that starts online must not fetch twice.
            coVerify(exactly = 1) { repository.getUserViews() }

            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getUserViews() }
            viewModel.uiState.value.libraries shouldContainExactly listOf(movies, shows)
        }
}
