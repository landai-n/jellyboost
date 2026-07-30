package dev.jellyfinnative.player.syncplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.player.syncplay.SyncPlayController
import dev.jellyfinnative.player.syncplay.SyncPlayState
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * What the group has queued, with names and pictures on it (M11 Phase 4).
 *
 * The queue itself is `SyncPlayController`'s — it arrives as playlist-item ids and library item ids
 * and nothing else, because that is all the protocol carries. This class is the half that makes it
 * readable: it fetches each item once, keeps what it fetched, and re-projects the rows whenever the
 * server re-sends the queue. A reorder or a removal therefore redraws without a single request.
 *
 * Every edit below is a **request to the server** and changes nothing here (key decision 11,
 * docs/notes/syncplay-m11-plan.md): the row does not move, the item is not removed and playback does
 * not jump until the server's own `PlayQueueUpdate` comes back with the new queue. That is what
 * makes the sheet show the same order to everyone in the group rather than an optimistic local one.
 */
@HiltViewModel
class SyncPlayQueueViewModel
    @Inject
    constructor(
        private val controller: SyncPlayController,
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        /** Everything fetched so far, keyed by library item id — never invalidated while open. */
        private val items = MutableStateFlow<Map<UUID, JellyfinItem>>(emptyMap())

        private val queue: StateFlow<SyncPlayGroupQueue?> =
            controller.state
                .map { (it as? SyncPlayState.InGroup)?.queue }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

        /** The rows to draw, in the group's order. */
        val uiState: StateFlow<SyncPlayQueueUiState> =
            combine(queue, items) { current, known -> current.toUiState(known) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncPlayQueueUiState())

        init {
            viewModelScope.launch {
                queue.collect { current -> hydrate(current?.entries?.map { it.itemId }.orEmpty()) }
            }
        }

        /** Jumps the whole group to a slot. */
        fun play(playlistItemId: UUID) {
            controller.requestSetPlaylistItem(playlistItemId)
        }

        /** The group's next / previous, which Phase 3 deliberately left without a call site. */
        fun next() {
            controller.requestNext()
        }

        fun previous() {
            controller.requestPrevious()
        }

        /**
         * Moves a slot by one place.
         *
         * Buttons rather than a drag: the row cannot follow a finger, because where it ends up is
         * the server's answer and not this device's (DECISIONS.md, 2026-07-30).
         */
        fun move(
            playlistItemId: UUID,
            newIndex: Int,
        ) {
            val size = uiState.value.rows.size
            if (newIndex !in 0 until size) return
            controller.moveQueueItem(playlistItemId, newIndex)
        }

        fun remove(playlistItemId: UUID) {
            controller.removeFromQueue(listOf(playlistItemId))
        }

        /**
         * Fetches the entries not fetched yet, a few at a time.
         *
         * One call per item rather than one call for the queue: the repository has no fetch-by-ids
         * (DECISIONS.md, 2026-07-30), and `getItem` is also the call that answers from the Room cache
         * with no server — which is what a group watching something this device has downloaded needs.
         * Bounded because a long queue would otherwise open a request per row at once.
         */
        private suspend fun hydrate(itemIds: List<UUID>) {
            val missing = itemIds.distinct().filterNot { items.value.containsKey(it) }
            if (missing.isEmpty()) return

            missing.chunked(FETCH_CONCURRENCY).forEach { chunk ->
                val fetched =
                    coroutineScope {
                        chunk
                            .map { id -> async { id to repository.getItem(id.toString()).getOrNull() } }
                            .awaitAll()
                    }
                val resolved = fetched.mapNotNull { (id, item) -> item?.let { id to it } }
                if (resolved.isNotEmpty()) items.update { it + resolved }
            }
        }

        private companion object {
            /**
             * How many item lookups are in flight at once.
             *
             * Small on purpose: the sheet is readable as soon as the first rows arrive, and a queue of
             * fifty would otherwise open fifty connections for something the user is scrolling past.
             */
            const val FETCH_CONCURRENCY = 6

            /** Keeps the projection alive across a configuration change, as every screen here does. */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** The queue sheet's state: the rows, and whether there is anything to draw at all. */
data class SyncPlayQueueUiState(
    val rows: List<SyncPlayQueueRow> = emptyList(),
    /** Index of the row playing now, or `-1` when the group is on nothing. */
    val playingIndex: Int = -1,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** `true` when the group has a slot after the one playing — what enables *Next*. */
    val hasNext: Boolean get() = playingIndex in 0 until rows.lastIndex

    /** `true` when the group has a slot before the one playing — what enables *Previous*. */
    val hasPrevious: Boolean get() = playingIndex > 0
}

/**
 * One slot, drawn.
 *
 * [playlistItemId] is what every edit names — the *slot*, not the item, since the same episode can
 * be queued twice. [title] falls back to a placeholder until the item is fetched, so the queue's
 * shape is visible immediately rather than after the last round trip.
 */
data class SyncPlayQueueRow(
    val playlistItemId: UUID,
    val itemId: UUID,
    val title: String?,
    val subtitle: String?,
    val imageUrl: String?,
    val isPlaying: Boolean,
)

private fun SyncPlayGroupQueue?.toUiState(items: Map<UUID, JellyfinItem>): SyncPlayQueueUiState {
    if (this == null) return SyncPlayQueueUiState()
    return SyncPlayQueueUiState(
        rows =
            entries.mapIndexed { index, entry ->
                val item = items[entry.itemId]
                SyncPlayQueueRow(
                    playlistItemId = entry.playlistItemId,
                    itemId = entry.itemId,
                    title = item?.displayTitle,
                    subtitle = item?.displaySubtitle,
                    imageUrl = item?.thumbImageUrl ?: item?.primaryImageUrl,
                    isPlaying = index == playingItemIndex,
                )
            },
        playingIndex = playingItemIndex.takeIf { it in entries.indices } ?: -1,
    )
}
