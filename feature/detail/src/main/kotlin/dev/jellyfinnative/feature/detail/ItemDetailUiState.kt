package dev.jellyfinnative.feature.detail

import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.UserData

/**
 * Everything the item detail screen draws.
 *
 * One state class covers all three shapes the plan lists for this screen — Movie, Series and
 * Season (docs/PLAN.md, "Screens" → ItemDetail). Which rows appear follows from [item]'s type, so
 * the screen never has to branch on a separate mode flag: a movie simply has no seasons, a season
 * no similar items.
 */
data class ItemDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val item: JellyfinItem? = null,
    /** Series detail: the show's seasons. */
    val seasons: List<JellyfinItem> = emptyList(),
    /** Season detail: the season's episodes. */
    val episodes: List<JellyfinItem> = emptyList(),
    /** Series detail: the next episode to watch, if any. */
    val nextUp: JellyfinItem? = null,
    /** Movie / series / episode detail: the *More like this* row. */
    val similar: List<JellyfinItem> = emptyList(),
    /** Set only when the item itself could not be loaded — a related row failing is silent. */
    val errorMessage: String? = null,
    /** A one-shot message for the snackbar; cleared by `ItemDetailViewModel.consumeMessage`. */
    val userMessage: UserMessage? = null,
) {
    /** `true` once the item is loaded and can be rendered. */
    val isLoaded: Boolean get() = !isLoading && item != null
}

/**
 * The one-shot messages the detail screen can raise.
 *
 * An enum rather than a string so the ViewModel stays free of resources and the copy lives in
 * `strings.xml` where it can be translated.
 */
enum class UserMessage {
    /** Play / Resume was tapped; playback lands in M5. */
    PlaybackNotAvailableYet,

    /** Download was tapped; the download pipeline lands in M7. */
    DownloadNotAvailableYet,

    /** A watched / favourite toggle could not even be written locally. */
    UserDataWriteFailed,
}

/**
 * Patches every item this state holds whose id matches [itemId].
 *
 * The detail page collects the same `UserDataEventBus` the home rows do, so a toggle is reflected
 * optimistically from the local write — no re-fetch, and no separate "pending" flag in the UI.
 */
internal fun ItemDetailUiState.withUserData(
    itemId: String,
    userData: UserData,
): ItemDetailUiState =
    copy(
        item = item?.patch(itemId, userData),
        seasons = seasons.patch(itemId, userData),
        episodes = episodes.patch(itemId, userData),
        nextUp = nextUp?.patch(itemId, userData),
        similar = similar.patch(itemId, userData),
    )

private fun JellyfinItem.patch(
    itemId: String,
    userData: UserData,
): JellyfinItem = if (id == itemId) copy(userData = userData) else this

private fun List<JellyfinItem>.patch(
    itemId: String,
    userData: UserData,
): List<JellyfinItem> = if (none { it.id == itemId }) this else map { it.patch(itemId, userData) }
