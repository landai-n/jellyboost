package dev.jellyfinnative.player.syncplay

import java.util.UUID

/**
 * The player screen, as far as [SyncPlayController] is concerned.
 *
 * The controller drives `PlayerHandle` directly for transport (so commands land while the app is
 * backgrounded), but it deliberately cannot *open* a playback session: resolving an item is the
 * ViewModel's job — device profile, downloaded copy versus stream, track choices, reporting,
 * decoder fallback — and duplicating any of that here would be a second playback pipeline.
 *
 * So the surface is the smallest one that lets the controller run the group handshake: open the
 * item, and tell me where you are. `PlayerViewModel` implements it in M11 Phase 3.
 */
interface SyncPlayPlaybackHost {
    /**
     * Opens [itemId] at [startPositionTicks] **paused**, and returns once it is preparing.
     *
     * Paused is not a detail: the group decides when playback starts, and a host that began playing
     * on its own would be out of sync from the first frame (docs/notes/syncplay-m11-plan.md, key
     * decision 11). The controller reports `buffering` before calling this and reports `ready` when
     * `PlayerEvent.Ready` arrives, so the implementation does not have to know the protocol.
     *
     * @return `false` when the item cannot be opened at all — the controller then tells the user
     *   rather than leaving the group waiting on a client that will never be ready.
     */
    suspend fun loadItem(
        itemId: UUID,
        startPositionTicks: Long,
    ): Boolean

    /**
     * What is loaded and where it is. Called on the main thread, like every `PlayerHandle` read.
     *
     * The controller uses it to avoid reloading an item the host already has open — the ordinary
     * case when the user opens an item and *then* the group moves to it.
     */
    fun snapshot(): SyncPlayHostSnapshot
}

/**
 * A point-in-time reading of the host.
 *
 * @param itemId the library item currently open, or `null` when nothing is loaded yet.
 */
data class SyncPlayHostSnapshot(
    val itemId: UUID?,
    val positionTicks: Long,
    val isPlaying: Boolean,
)
