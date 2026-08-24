package dev.jellyboost.data.downloads

import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import timber.log.Timber

/**
 * The app-wide download-state map, guarded for the screens that draw badges from it.
 *
 * A collapse degrades to "nothing is downloaded", never to stale marks: a badge is decoration, the
 * screen behind it is not, and an unguarded throw would freeze every badge at its last value with no
 * way back — marks the user would read as current. An empty map makes them disappear instead, and the
 * next successful emission restores them.
 *
 * @param screen names the surface in the warning only; it is a log label, never user-visible copy.
 */
fun DownloadRepository.observeBadgeStates(screen: String): Flow<Map<String, DownloadState>> =
    observeStates().catch { error ->
        Timber.w(error, "The download-state flow failed; clearing the %s badges", screen)
        emit(emptyMap())
    }

/**
 * Stamps [states] onto one item. An item the map does not mention is [DownloadState.NotDownloaded]
 * rather than "unknown": the map is the whole truth about what is on the device.
 */
fun JellyfinItem.withDownloadState(states: Map<String, DownloadState>): JellyfinItem {
    val next = states[id] ?: DownloadState.NotDownloaded
    return if (next == downloadState) this else copy(downloadState = next)
}

/**
 * Stamps [states] onto every item in the list, **preserving identity when nothing changed**.
 *
 * The identity check is the load-bearing part and the reason this is not a plain `map`: the queue
 * writes throttled progress several times a second, so returning a fresh list each time would make
 * Compose re-compose rows in which not one item moved. Returning `this` lets an unaffected `LazyRow`
 * skip the whole recomposition, and the same `===` lets a caller decide whether to copy its own state.
 */
fun List<JellyfinItem>.withDownloadStates(states: Map<String, DownloadState>): List<JellyfinItem> {
    val patched = map { it.withDownloadState(states) }
    return if (patched.indices.all { patched[it] === this[it] }) this else patched
}
