package dev.jellyboost.player.syncplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
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
 * Names and pictures for the ids `SyncPlayController`'s queue carries.
 *
 * Every edit here is a **request to the server** and must change nothing locally: rows move only
 * when the server's `PlayQueueUpdate` comes back, which is what keeps every member's order identical.
 */
@HiltViewModel
internal class SyncPlayQueueViewModel
    @Inject
    constructor(
        private val controller: SyncPlayController,
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        /** Keyed by library item id; never invalidated while open. */
        private val items = MutableStateFlow<Map<UUID, JellyfinItem>>(emptyMap())

        /**
         * Ids the repository refused. Needed because the server re-sends the whole `PlayQueueUpdate`
         * on every play, pause and seek: without this, an undescribable item re-fires `getItem` for
         * as long as the sheet is open.
         *
         * [unresolvedFor] holds the queue *membership* those refusals were collected under, so a
         * genuine queue/unqueue drops them and retries while a reorder or transport action does not.
         */
        private var unresolved = emptySet<UUID>()

        private var unresolvedFor = emptySet<UUID>()

        private val queue: StateFlow<SyncPlayGroupQueue?> =
            controller.state
                .map { (it as? SyncPlayState.InGroup)?.queue }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

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

        fun next() {
            controller.requestNext()
        }

        fun previous() {
            controller.requestPrevious()
        }

        /** Buttons, never a drag: the row cannot follow a finger when the server decides where it lands. */
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
         * One call per item: the repository has no fetch-by-ids, and `getItem` is also the call that
         * answers from the Room cache with no server, which a group watching a downloaded item needs.
         */
        private suspend fun hydrate(itemIds: List<UUID>) {
            val queued = itemIds.toSet()
            if (queued != unresolvedFor) {
                // Membership changed, so this is a different question from the one that was refused.
                unresolved = emptySet()
                unresolvedFor = queued
            }

            val missing = itemIds.distinct().filterNot { items.value.containsKey(it) || it in unresolved }
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
                unresolved = unresolved + fetched.filter { (_, item) -> item == null }.map { (id, _) -> id }
            }
        }

        private companion object {
            /** Item lookups in flight at once: small so a fifty-row queue does not open fifty. */
            const val FETCH_CONCURRENCY = 6

            /** Keeps the projection alive across a configuration change. */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

internal data class SyncPlayQueueUiState(
    val rows: List<SyncPlayQueueRow> = emptyList(),
    /** `-1` when the group is on nothing. */
    val playingIndex: Int = -1,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    val hasNext: Boolean get() = playingIndex in 0 until rows.lastIndex

    val hasPrevious: Boolean get() = playingIndex > 0
}

/** [playlistItemId] is what every edit names — the *slot*, since the same episode can be queued twice. */
internal data class SyncPlayQueueRow(
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
