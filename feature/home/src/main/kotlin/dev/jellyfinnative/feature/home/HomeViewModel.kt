package dev.jellyfinnative.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.data.ConnectivityRefresher
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.userdata.UserDataEventBus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the home screen.
 *
 * Loads the libraries first (every *Latest …* row is keyed off one), then fetches *Continue
 * watching*, *Next up* and every *Latest* row concurrently so the screen is bound by the slowest
 * single request rather than by their sum.
 *
 * Failure policy: only a failing `getUserViews` produces an error screen — without libraries there
 * is nothing to render. An individual row that fails is left empty, matching jellyfin-web, which
 * simply omits a section it could not load instead of blanking the page.
 *
 * `@HiltViewModel` makes this reachable from the `:app` Hilt graph via `hiltViewModel()` in the
 * NavHost; it requires the `org.jellyfin.sdk.api.client.ApiClient` binding `:core:network`
 * provides (see its `di/NetworkModule.kt`, `ApiClientModule`).
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val userDataEvents: UserDataEventBus,
        private val downloads: DownloadRepository,
        private val connectivityRefresher: ConnectivityRefresher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())

        /** The single source of truth for [HomeScreen]. */
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        /** Last download-state map seen, re-applied whenever a load replaces the rows. */
        private var downloadStates: Map<String, DownloadState> = emptyMap()

        init {
            load(isRefresh = false)
            observeUserDataChanges()
            observeDownloadStates()
            observeConnectivityChanges()
        }

        /**
         * Swaps the rows for the other source's the moment the connection changes (M9).
         *
         * Both directions matter. A home screen opened in airplane mode kept showing downloaded
         * media after the server came back; one opened online kept showing *its* rows — links to
         * media the app can no longer play — after the user pinned offline mode or lost the
         * network. `refresh()` is the same load either way: the repository picks the source.
         */
        private fun observeConnectivityChanges() {
            viewModelScope.launch {
                connectivityRefresher.connectivityChanged.collect { refresh() }
            }
        }

        /**
         * Keeps the `DownloadBadge` on every home card current (M7).
         *
         * One subscription for the whole screen: the home rows can hold sixty cards between them,
         * and the badge map re-emits on every throttled progress write.
         */
        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads.observeStates().collect { states ->
                    downloadStates = states
                    _uiState.update { it.withDownloadStates(states) }
                }
            }
        }

        /**
         * Patches the loaded rows in place whenever user data changes anywhere in the app.
         *
         * Deliberately not a refresh: M4's definition of done is that marking an item watched on
         * its detail page updates the home row **without a refetch** (docs/PLAN.md, "Data layer").
         */
        private fun observeUserDataChanges() {
            viewModelScope.launch {
                userDataEvents.changes.collect { change ->
                    _uiState.update { it.withUserData(change.itemId, change.userData) }
                }
            }
        }

        /** Re-fetches every row; called by pull-to-refresh and by the error state's retry button. */
        fun refresh() {
            load(isRefresh = true)
        }

        private fun load(isRefresh: Boolean) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(isLoading = !isRefresh, isRefreshing = isRefresh, errorMessage = null)
                }

                when (val views = repository.getUserViews()) {
                    is AppResult.Failure ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                errorMessage = views.error.toMessage(),
                            )
                        }

                    is AppResult.Success -> emitRows(views.value)
                }
            }
        }

        private suspend fun emitRows(libraries: List<LibraryView>) {
            val rows = fetchRows(libraries)
            _uiState.update {
                it
                    .copy(
                        isLoading = false,
                        isRefreshing = false,
                        libraries = rows.libraries,
                        resume = rows.resume,
                        nextUp = rows.nextUp,
                        latest = rows.latest,
                        errorMessage = null,
                    ).withDownloadStates(downloadStates)
            }
        }

        private suspend fun fetchRows(libraries: List<LibraryView>): Rows =
            coroutineScope {
                val resume = async { repository.getResumeItems().getOrNull().orEmpty() }
                val nextUp = async { repository.getNextUp().getOrNull().orEmpty() }
                val latest =
                    libraries
                        .map { library -> async { library to repository.getLatestMedia(library.id) } }
                        .awaitAll()

                Rows(
                    // A library whose *Latest* call succeeded and came back empty has nothing
                    // behind it — offline that is every library with no downloads in it, since
                    // `getUserViews` still answers from the full cached list. A library whose call
                    // *failed* is kept: one flaky request must not delete a library card.
                    libraries = latest.filterNot { (_, items) -> items.isKnownEmpty() }.map { it.first },
                    resume = resume.await(),
                    nextUp = nextUp.await(),
                    latest =
                        latest.mapNotNull { (library, result) ->
                            result
                                .getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { LatestSection(library, it) }
                        },
                )
            }

        /** `true` only when the server (or Room) answered, and answered with nothing. */
        private fun AppResult<List<JellyfinItem>>.isKnownEmpty(): Boolean = this is AppResult.Success && value.isEmpty()

        private data class Rows(
            val libraries: List<LibraryView>,
            val resume: List<JellyfinItem>,
            val nextUp: List<JellyfinItem>,
            val latest: List<LatestSection>,
        )
    }

/** Turns the domain failure taxonomy into copy a user can act on. */
internal fun AppError.toMessage(): String =
    when (this) {
        is AppError.Network -> "Can't reach your server. Check your connection and try again."
        is AppError.ServerResolution -> "Can't reach your server. Check your connection and try again."
        is AppError.Unauthorized -> "Your session expired. Sign in again to continue."
        is AppError.NotFound -> "That library is no longer on the server."
        is AppError.Server -> "The server returned an error${statusCode?.let { " ($it)" }.orEmpty()}."
        is AppError.Storage -> "Couldn't read local data."
        is AppError.Unknown -> "Something went wrong loading your home screen."
    }
