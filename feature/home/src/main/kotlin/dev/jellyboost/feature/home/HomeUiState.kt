package dev.jellyboost.feature.home

import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.HomeSectionType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.data.downloads.withDownloadStates
import dev.jellyboost.data.homelayout.DEFAULT_HOME_SECTIONS

/** [sections], not this class's field order, decides what is drawn and in which order. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /**
     * Unimplemented sections (audio/book resume, live TV) are carried faithfully and skipped at
     * render time — dropping them would reorder everything after them.
     */
    val sections: List<HomeSectionType> = DEFAULT_HOME_SECTIONS,
    val libraries: List<LibraryView> = emptyList(),
    val resume: List<JellyfinItem> = emptyList(),
    val nextUp: List<JellyfinItem> = emptyList(),
    val latest: List<LatestSection> = emptyList(),
    val resumeAudio: List<JellyfinItem> = emptyList(),
    /** Set only when the screen has nothing to show; a single failed row is left empty instead. */
    val errorMessage: UiText? = null,
) {
    val isEmpty: Boolean
        get() =
            libraries.isEmpty() &&
                resume.isEmpty() &&
                nextUp.isEmpty() &&
                resumeAudio.isEmpty() &&
                latest.all { it.items.isEmpty() }
}

data class LatestSection(
    val library: LibraryView,
    val items: List<JellyfinItem>,
)

/**
 * Rows not containing [itemId] are returned by identity, so Compose skips them entirely — preserve
 * that when editing.
 */
internal fun HomeUiState.withUserData(
    itemId: String,
    userData: UserData,
): HomeUiState =
    copy(
        resume = resume.patchUnwatchedRow(itemId, userData),
        nextUp = nextUp.patchUnwatchedRow(itemId, userData),
        resumeAudio = resumeAudio.patchUnwatchedRow(itemId, userData),
        latest =
            latest.map { section ->
                val patched = section.items.patch(itemId, userData)
                if (patched === section.items) section else section.copy(items = patched)
            },
    )

/** *Continue watching* and *Next up* hold unfinished items, so a played item is evicted, not patched. */
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
 * A server read is authoritative *unless a local write is still waiting to be pushed* — the rule
 * `StaleUserDataRegressionTest` pins in `:data`. [known] is that guard: without it a slow or queued
 * push lets a re-fetch resurrect the state the user just changed.
 */
internal fun List<JellyfinItem>.mergeLocalUserData(known: Map<String, UserData>): List<JellyfinItem> =
    mapNotNull { item ->
        val merged = known[item.id]?.let { item.copy(userData = it) } ?: item
        merged.takeUnless { it.userData.played }
    }

internal fun HomeUiState.withDownloadStates(states: Map<String, DownloadState>): HomeUiState =
    copy(
        resume = resume.withDownloadStates(states),
        nextUp = nextUp.withDownloadStates(states),
        resumeAudio = resumeAudio.withDownloadStates(states),
        latest =
            latest.map { section ->
                val patched = section.items.withDownloadStates(states)
                if (patched === section.items) section else section.copy(items = patched)
            },
    )
