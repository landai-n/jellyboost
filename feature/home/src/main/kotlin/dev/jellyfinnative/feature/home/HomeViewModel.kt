package dev.jellyfinnative.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.data.JellyfinRepository
import kotlinx.coroutines.async
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
 * **Integration note (M2 → M1).** The constructor is `@Inject`-annotated but the class is
 * deliberately *not* `@HiltViewModel` yet: that annotation makes the ViewModel reachable from the
 * `:app` Hilt graph, which then requires the `org.jellyfin.sdk.api.client.ApiClient` binding that
 * lives in `:core:network` (M1 scope). Add `@HiltViewModel` together with the NavHost wiring once
 * that binding exists — see DECISIONS.md, 2026-07-28 "HomeViewModel not @HiltViewModel yet".
 */
class HomeViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())

        /** The single source of truth for [HomeScreen]. */
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        init {
            load(isRefresh = false)
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
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    libraries = libraries,
                    resume = rows.resume,
                    nextUp = rows.nextUp,
                    latest = rows.latest,
                    errorMessage = null,
                )
            }
        }

        private suspend fun fetchRows(libraries: List<LibraryView>): Rows =
            coroutineScope {
                val resume = async { repository.getResumeItems().getOrNull().orEmpty() }
                val nextUp = async { repository.getNextUp().getOrNull().orEmpty() }
                val latest =
                    libraries.map { library ->
                        async {
                            LatestSection(
                                library = library,
                                items = repository.getLatestMedia(library.id).getOrNull().orEmpty(),
                            )
                        }
                    }
                Rows(
                    resume = resume.await(),
                    nextUp = nextUp.await(),
                    latest = latest.map { it.await() }.filter { it.items.isNotEmpty() },
                )
            }

        private data class Rows(
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
