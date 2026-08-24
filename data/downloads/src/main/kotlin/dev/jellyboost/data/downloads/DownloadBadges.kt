package dev.jellyboost.data.downloads

import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import timber.log.Timber

/**
 * The app-wide download-state map, guarded for the screens that draw badges from it.
 *
 * Every list screen wants the same two things from [DownloadRepository.observeStates], and they are
 * stated here once rather than in a `catch` block per screen:
 *
 * 1. **One subscription per screen, not one per card.** A home screen holds sixty cards between its
 *    rows and an episode list forty; `observeStates()` is a shared `stateIn` over a single Room
 *    query precisely so a screen subscribes once and indexes the map.
 * 2. **A collapse degrades to "nothing is downloaded", never to stale marks.** A badge is
 *    decoration; the screen behind it is not. An unguarded throw would freeze every badge on the
 *    screen at its last value with no way back — marks the user would read as current. Emitting an
 *    empty map instead is the honest failure: the badges disappear, the screen keeps working, and
 *    the next successful emission restores them.
 *
 * @param screen names the surface in the warning only ("home", "search", "detail", "library"); it
 *   is a log label, never user-visible copy.
 */
fun DownloadRepository.observeBadgeStates(screen: String): Flow<Map<String, DownloadState>> =
    observeStates().catch { error ->
        Timber.w(error, "The download-state flow failed; clearing the %s badges", screen)
        emit(emptyMap())
    }

/**
 * Stamps [states] onto one item, or returns it unchanged.
 *
 * An item the map does not mention is [DownloadState.NotDownloaded] rather than "unknown": the map
 * is the whole truth about what is on the device, so an absent id is a positive answer.
 */
fun JellyfinItem.withDownloadState(states: Map<String, DownloadState>): JellyfinItem {
    val next = states[id] ?: DownloadState.NotDownloaded
    return if (next == downloadState) this else copy(downloadState = next)
}

/**
 * Stamps [states] onto every item in the list, **preserving identity when nothing changed**.
 *
 * `:core:ui`'s cards render their `DownloadBadge` straight from [JellyfinItem.downloadState], so
 * this one function is the whole of "every card shows a download badge" for every list screen in
 * the app — the cards themselves need no change.
 *
 * The identity check is the load-bearing part, and the reason this is not a plain `map`. The queue
 * writes throttled progress several times a second, so this runs several times a second over every
 * loaded row; returning a fresh list each time would make Compose re-compose rows in which not one
 * item moved. Returning `this` when every element came back identical lets a `LazyRow` whose items
 * are unaffected skip the whole recomposition, and the same `===` comparison lets a caller decide
 * whether to copy its own enclosing state at all.
 */
fun List<JellyfinItem>.withDownloadStates(states: Map<String, DownloadState>): List<JellyfinItem> {
    val patched = map { it.withDownloadState(states) }
    return if (patched.indices.all { patched[it] === this[it] }) this else patched
}
