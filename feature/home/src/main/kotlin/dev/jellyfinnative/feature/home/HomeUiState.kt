package dev.jellyfinnative.feature.home

import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView

/**
 * Everything the home screen draws, in the order jellyfin-web draws it: *My Media*, *Continue
 * Watching*, *Next Up*, then one *Latest …* row per library (docs/PLAN.md, "Screens" → Home).
 * Matching that order is the M2 definition of done.
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val libraries: List<LibraryView> = emptyList(),
    val resume: List<JellyfinItem> = emptyList(),
    val nextUp: List<JellyfinItem> = emptyList(),
    val latest: List<LatestSection> = emptyList(),
    /**
     * Set only when the screen has nothing to show. A row that fails on its own is left empty
     * rather than blanking the whole screen.
     */
    val errorMessage: String? = null,
) {
    /** `true` when the load succeeded but the server returned nothing to show. */
    val isEmpty: Boolean
        get() =
            libraries.isEmpty() &&
                resume.isEmpty() &&
                nextUp.isEmpty() &&
                latest.all { it.items.isEmpty() }
}

/** One *Latest &lt;library&gt;* row: the library it belongs to plus its most recent additions. */
data class LatestSection(
    val library: LibraryView,
    val items: List<JellyfinItem>,
)
