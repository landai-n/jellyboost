package dev.jellyfinnative.feature.home

import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.HomeSectionType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.data.homelayout.DEFAULT_HOME_SECTIONS

/**
 * Everything the home screen draws, in the order jellyfin-web draws it: by default *My Media*,
 * *Continue Watching*, *Next Up*, then one *Latest …* row per library (docs/PLAN.md, "Screens" →
 * Home). Matching that order is the M2 definition of done.
 *
 * Since the layout became server-configurable, [sections] — not this class's field order — decides
 * what is drawn and in which order; the default value of [sections] is exactly the order above.
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /**
     * The rows to draw, in order, as configured in jellyfin-web's Settings → Home.
     *
     * Resolved on every full load. Sections this app does not implement (audio/book resume, live
     * TV) are carried faithfully — dropping them would reorder everything after them — and skipped
     * at render time.
     */
    val sections: List<HomeSectionType> = DEFAULT_HOME_SECTIONS,
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
 * *Continue watching* and *Next up* hold, by definition, items that are **not** finished, so a
 * change that says the item is played does not patch it there — it removes it. That is the
 * membership half of the same instant, request-free update: marking a movie watched makes it leave
 * *Continue watching* in the same frame the tick appears elsewhere, online or off (see
 * `docs/features/user-data.md`). What a patch cannot synthesise — the *next* episode that should
 * take a watched one's place in *Next up*, or an item coming **back** after being un-marked — is
 * the job of `HomeViewModel`'s debounced membership refresh.
 *
 * Rows that do not contain [itemId] are returned untouched — identity is preserved, so Compose
 * skips them entirely.
 */
internal fun HomeUiState.withUserData(
    itemId: String,
    userData: UserData,
): HomeUiState =
    copy(
        resume = resume.patchUnwatchedRow(itemId, userData),
        nextUp = nextUp.patchUnwatchedRow(itemId, userData),
        latest =
            latest.map { section ->
                val patched = section.items.patch(itemId, userData)
                if (patched === section.items) section else section.copy(items = patched)
            },
    )

/** A row of unfinished items: the patch either updates the item or evicts it once it is played. */
private fun List<JellyfinItem>.patchUnwatchedRow(
    itemId: String,
    userData: UserData,
): List<JellyfinItem> =
    when {
        none { it.id == itemId } -> this
        userData.played -> filterNot { it.id == itemId }
        else -> patch(itemId, userData)
    }

private fun List<JellyfinItem>.patch(
    itemId: String,
    userData: UserData,
): List<JellyfinItem> =
    if (none { it.id == itemId }) {
        this
    } else {
        map { if (it.id == itemId) it.copy(userData = userData) else it }
    }

/**
 * Re-applies the local user-data changes seen this session to a freshly fetched *Continue watching*
 * or *Next up* row, and drops whatever those changes say is already watched.
 *
 * A server read is authoritative *unless a local write is still waiting to be pushed* — the rule
 * `StaleUserDataRegressionTest` pins in `:data`. The home rows are re-fetched moments after a
 * toggle, so a push that is slow, or that failed and is now queued for `UserDataSyncWorker`, would
 * otherwise resurrect the state the user just changed. [known] is that guard: the last value the
 * app itself published for each item, which is also the newest one it knows of.
 */
internal fun List<JellyfinItem>.mergeLocalUserData(known: Map<String, UserData>): List<JellyfinItem> =
    mapNotNull { item ->
        val merged = known[item.id]?.let { item.copy(userData = it) } ?: item
        merged.takeUnless { it.userData.played }
    }

/**
 * Stamps the app-wide download-state map onto every card the home screen holds (M7).
 *
 * `:core:ui`'s cards render their `DownloadBadge` from `JellyfinItem.downloadState`, so this one
 * function is the whole of "every item card shows a download badge" for this screen — the cards
 * themselves need no change.
 */
internal fun HomeUiState.withDownloadStates(states: Map<String, DownloadState>): HomeUiState =
    copy(
        resume = resume.withDownloadStates(states),
        nextUp = nextUp.withDownloadStates(states),
        latest =
            latest.map { section ->
                val patched = section.items.withDownloadStates(states)
                if (patched === section.items) section else section.copy(items = patched)
            },
    )

/** Identity is preserved when nothing changed, so Compose skips the untouched rows entirely. */
private fun List<JellyfinItem>.withDownloadStates(states: Map<String, DownloadState>): List<JellyfinItem> {
    val patched =
        map { item ->
            val next = states[item.id] ?: DownloadState.NotDownloaded
            if (next == item.downloadState) item else item.copy(downloadState = next)
        }
    return if (patched.indices.all { patched[it] === this[it] }) this else patched
}
