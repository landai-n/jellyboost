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
     *
     * For a season it is not this item's own state at all but the *aggregate* of its episodes',
     * because a season is downloaded by downloading its episodes ([aggregateDownloadState]).
     */
    val downloadState: DownloadState = DownloadState.NotDownloaded,
    /** Set only when the item itself could not be loaded — a related row failing is silent. */
    val errorMessage: String? = null,
    /** A one-shot message for the snackbar; cleared by `ItemDetailViewModel.consumeMessage`. */
    val userMessage: UserMessage? = null,
    /**
     * `true` while the delete-download confirmation dialog is up (docs/POLISH.md).
     *
     * Set by `ItemDetailViewModel.onDownloadClick` instead of deleting straight away whenever the
     * download button's next tap would remove something already on the device — cleared again by
     * either `confirmDeleteDownload` or `dismissDeleteConfirmation`.
     */
    val showDeleteConfirmation: Boolean = false,
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

    /**
     * `true` when Download on this page means "download the episodes under this".
     *
     * A season and a series are folders: the server has no file to send for one, and the pipeline
     * expands them into episode downloads (DECISIONS.md, 2026-07-29). The button therefore acts on
     * [downloadTargets], not on the item's own id.
     */
    val isDownloadContainer: Boolean get() = item?.type == ItemType.SERIES || item?.type == ItemType.SEASON

    /**
     * The ids a *delete* or *cancel* from the Download button acts on.
     *
     * For a movie or an episode that is the item itself; for a season it is its episodes, since
     * those are the rows that exist. A season page that has not loaded its episodes yields nothing,
     * which is correct — there is nothing on the device to remove that this page knows about.
     */
    val downloadTargets: List<String>
        get() =
            when {
                item == null -> emptyList()
                isDownloadContainer -> episodes.map { it.id }
                else -> listOf(item.id)
            }
}

/**
 * One download state for a container, from the states of the episodes under it.
 *
 * The order of the cases is the order of what the user would want the button to do next: finish
 * what is running, retry what failed, and only then offer to remove what is complete.
 *
 * - **everything downloaded** → *Downloaded*, and the button offers to remove it;
 * - **anything transferring** → *Downloading*, with the progress of the whole container: episodes
 *   already done count as one, ones not started as zero, so a season at "3 of 10" reads ~30 % and
 *   not 100 % of whichever episode happens to be moving;
 * - **anything waiting or paused** → *Queued*;
 * - **anything failed**, with nothing left running → *Failed*, and the button retries;
 * - **anything else**, including a container only partly downloaded with nothing in the queue →
 *   *NotDownloaded*, so the next tap enqueues the episodes that are still missing.
 */
internal fun aggregateDownloadState(states: List<DownloadState>): DownloadState =
    when {
        states.isEmpty() -> DownloadState.NotDownloaded
        states.all { it is DownloadState.Downloaded } -> DownloadState.Downloaded
        states.any { it is DownloadState.Downloading } ->
            DownloadState.Downloading(progress = states.sumOf { it.fraction.toDouble() }.toFloat() / states.size)

        states.any { it is DownloadState.Queued || it is DownloadState.Paused } -> DownloadState.Queued
        states.any { it is DownloadState.Failed } -> DownloadState.Failed
        else -> DownloadState.NotDownloaded
    }

/** How much of one item is on the device, `0f..1f` — the term each episode contributes above. */
private val DownloadState.fraction: Float
    get() =
        when (this) {
            is DownloadState.Downloaded -> 1f
            is DownloadState.Downloading -> progress
            else -> 0f
        }

/** Where playback should start for [item]: its resume position, or the beginning. */
fun playbackStartTicks(item: JellyfinItem): Long =
    if (item.userData.isResumable) item.userData.playbackPositionTicks else 0L

/**
 * The one-shot messages the detail screen can raise.
 *
 * A type rather than a string so the ViewModel stays free of resources and the copy lives in
 * `strings.xml` where it can be translated — a sealed interface rather than an enum because one of
 * the messages carries a count (precedent: `:feature:auth`'s `AuthErrorMessage`).
 */
sealed interface UserMessage {
    /** The item was accepted into the download queue. */
    data object DownloadQueued : UserMessage

    /** The enqueue failed — usually because the server could not be reached. */
    data object DownloadFailed : UserMessage

    /** The item and its files were removed from the device. */
    data object DownloadDeleted : UserMessage

    /** Deleting the download failed. */
    data object DownloadDeleteFailed : UserMessage

    /**
     * A container download was cancelled while [keptCount] of its episodes were already finished —
     * those were left on the device (DECISIONS.md, 2026-07-29).
     *
     * Worth saying out loud: afterwards the button simply offers *Download* for the missing
     * episodes, and without this the user cannot tell whether the finished ones survived.
     */
    data class DownloadCancelledKeepingFinished(
        val keptCount: Int,
    ) : UserMessage

    /** A watched / favourite toggle could not even be written locally. */
    data object UserDataWriteFailed : UserMessage
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
        downloadState = resolveDownloadState(states),
        item = item?.withDownloadState(states),
        seasons = seasons.withDownloadStates(states),
        episodes = episodes.withDownloadStates(states),
        nextUp = nextUp?.withDownloadState(states),
        similar = similar.withDownloadStates(states),
    )

/**
 * What the header's Download button reads.
 *
 * A season has no download row of its own — the pipeline expands it into its episodes — so its
 * state is theirs, aggregated. Everything else, including a season page whose episodes have not
 * arrived yet, reads its own row.
 */
private fun ItemDetailUiState.resolveDownloadState(states: Map<String, DownloadState>): DownloadState {
    val current = item ?: return DownloadState.NotDownloaded

    if (isDownloadContainer && episodes.isNotEmpty()) {
        return aggregateDownloadState(episodes.map { states[it.id] ?: DownloadState.NotDownloaded })
    }
    return states[current.id] ?: DownloadState.NotDownloaded
}

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
