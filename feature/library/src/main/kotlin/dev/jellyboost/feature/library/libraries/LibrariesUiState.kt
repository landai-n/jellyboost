package dev.jellyboost.feature.library.libraries

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.LibraryView

/**
 * Everything the Libraries tab draws: the current user's movie and TV libraries as a browsable
 * grid (docs/PLAN.md, "Confirmed decisions" — bottom nav bar Home / Libraries / Search /
 * Downloads).
 *
 * This is the same [dev.jellyboost.data.JellyfinRepository.getUserViews] call the home
 * screen's *My Media* row already makes; the tab exists so the whole library list is one tap away
 * without going through Home.
 */
data class LibrariesUiState(
    val isLoading: Boolean = true,
    val libraries: List<LibraryView> = emptyList(),
    val error: AppError? = null,
) {
    /** `true` when the load succeeded but the server has no libraries to show. */
    val isEmpty: Boolean
        get() = libraries.isEmpty() && error == null
}
