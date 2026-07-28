package dev.jellyfinnative.feature.home

import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.common.model.UserData

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

/**
 * Applies a local user-data change to every row that happens to contain the item.
 *
 * This is the mechanism behind M4's "home row patches without refetch": marking an episode watched
 * on its detail page publishes on `UserDataEventBus`, and the home rows behind it redraw from the
 * patched state without a single request (docs/PLAN.md, "Data layer").
 *
 * Rows that do not contain [itemId] are returned untouched — identity is preserved, so Compose
 * skips them entirely.
 */
internal fun HomeUiState.withUserData(
    itemId: String,
    userData: UserData,
): HomeUiState =
    copy(
        resume = resume.patch(itemId, userData),
        nextUp = nextUp.patch(itemId, userData),
        latest =
            latest.map { section ->
                val patched = section.items.patch(itemId, userData)
                if (patched === section.items) section else section.copy(items = patched)
            },
    )

private fun List<JellyfinItem>.patch(
    itemId: String,
    userData: UserData,
): List<JellyfinItem> =
    if (none { it.id == itemId }) {
        this
    } else {
        map { if (it.id == itemId) it.copy(userData = userData) else it }
    }
