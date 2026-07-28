package dev.jellyfinnative.feature.detail

import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.ItemType
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
    /**
     * Live download state of [item] (M7).
     *
     * Kept next to the item rather than folded into `JellyfinItem.downloadState` because it comes
     * from a *different* Flow — Room's download table — and updates several times a second while a
     * transfer runs, whereas the item itself is fetched once.
     */
    val downloadState: DownloadState = DownloadState.NotDownloaded,
    /** Set only when the item itself could not be loaded — a related row failing is silent. */
    val errorMessage: String? = null,
    /** A one-shot message for the snackbar; cleared by `ItemDetailViewModel.consumeMessage`. */
    val userMessage: UserMessage? = null,
) {
    /** `true` once the item is loaded and can be rendered. */
    val isLoaded: Boolean get() = !isLoading && item != null

    /**
     * What the Play / Resume button actually plays.
     *
     * A movie or episode plays itself, but a series or season is a container: tapping Play on
     * *Westworld* has to resolve to an episode, and the one the user expects is the one the server
     * already calls "next up". A season falls back to its first unfinished episode, then to its
     * first episode — the same order jellyfin-web uses.
     */
    val playTarget: JellyfinItem?
        get() =
            when (item?.type) {
                null -> null
                ItemType.SERIES -> nextUp ?: episodes.firstOrNull()
                ItemType.SEASON -> episodes.firstOrNull { !it.userData.played } ?: episodes.firstOrNull()
                else -> item
            }
}

/** Where playback should start for [item]: its resume position, or the beginning. */
fun playbackStartTicks(item: JellyfinItem): Long =
    if (item.userData.isResumable) item.userData.playbackPositionTicks else 0L

/**
 * The one-shot messages the detail screen can raise.
 *
 * An enum rather than a string so the ViewModel stays free of resources and the copy lives in
 * `strings.xml` where it can be translated.
 */
enum class UserMessage {
    /** The item was accepted into the download queue. */
    DownloadQueued,

    /** The enqueue failed — usually because the server could not be reached. */
    DownloadFailed,

    /** The item and its files were removed from the device. */
    DownloadDeleted,

    /** Deleting the download failed. */
    DownloadDeleteFailed,

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

/**
 * Applies the app-wide download-state map to the header and to every card this screen draws.
 *
 * `JellyfinItem.downloadState` is what `:core:ui`'s cards render their badge from, so patching the
 * items is what makes a season poster show a tick the moment its episodes finish downloading —
 * without the detail screen knowing anything about badges.
 */
internal fun ItemDetailUiState.withDownloadStates(states: Map<String, DownloadState>): ItemDetailUiState =
    copy(
        downloadState = item?.id?.let { states[it] } ?: DownloadState.NotDownloaded,
        item = item?.withDownloadState(states),
        seasons = seasons.withDownloadStates(states),
        episodes = episodes.withDownloadStates(states),
        nextUp = nextUp?.withDownloadState(states),
        similar = similar.withDownloadStates(states),
    )

private fun JellyfinItem.withDownloadState(states: Map<String, DownloadState>): JellyfinItem {
    val next = states[id] ?: DownloadState.NotDownloaded
    return if (next == downloadState) this else copy(downloadState = next)
}

/** Identity is preserved when nothing changed, so Compose can skip the whole row. */
private fun List<JellyfinItem>.withDownloadStates(states: Map<String, DownloadState>): List<JellyfinItem> {
    val patched = map { it.withDownloadState(states) }
    return if (patched.indices.all { patched[it] === this[it] }) this else patched
}

private fun JellyfinItem.patch(
    itemId: String,
    userData: UserData,
): JellyfinItem = if (id == itemId) copy(userData = userData) else this

private fun List<JellyfinItem>.patch(
    itemId: String,
    userData: UserData,
): List<JellyfinItem> = if (none { it.id == itemId }) this else map { it.patch(itemId, userData) }
