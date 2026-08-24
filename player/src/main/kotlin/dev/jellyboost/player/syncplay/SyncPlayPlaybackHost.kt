package dev.jellyboost.player.syncplay

import java.util.UUID

/**
 * Kept to the smallest surface the group handshake needs; the controller must not gain the ability to
 * resolve an item itself, or there would be a second playback pipeline beside the ViewModel's.
 */
interface SyncPlayPlaybackHost {
    /**
     * Opens [itemId] at [startPositionTicks] **paused** — the group decides when playback starts, and
     * a host that begins on its own is out of sync from the first frame. Returns once it is preparing.
     *
     * @return `false` when the item cannot be opened at all, so the controller can tell the user
     *   rather than leave the group waiting on a client that will never be ready.
     */
    suspend fun loadItem(
        itemId: UUID,
        startPositionTicks: Long,
    ): Boolean

    /** Called on the main thread, like every `PlayerHandle` read. */
    fun snapshot(): SyncPlayHostSnapshot
}

/** @param itemId `null` when nothing is loaded yet. */
data class SyncPlayHostSnapshot(
    val itemId: UUID?,
    val positionTicks: Long,
    val isPlaying: Boolean,
)
