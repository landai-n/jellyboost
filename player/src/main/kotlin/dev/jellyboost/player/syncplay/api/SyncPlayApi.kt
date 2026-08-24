package dev.jellyboost.player.syncplay.api

import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyboost.player.syncplay.model.TimeSyncSample
import java.time.Instant
import java.util.UUID

/**
 * Speaks **domain models and [Instant] only**: the SDK's DTOs and its local wall-clock
 * `LocalDateTime` stop at `SdkSyncPlayApi` / `SyncPlayDtoMapping`.
 *
 * `request*` calls only ask — they take effect when the server broadcasts the command back.
 */
@Suppress("TooManyFunctions") // The SyncPlay protocol has 22 operations; a partial facade is worse.
internal interface SyncPlayApi {
    // Group membership --------------------------------------------------------------------------

    /** `GET /SyncPlay/List`. */
    suspend fun getGroups(): List<SyncPlayGroupSummary>

    /** `POST /SyncPlay/New` — creates a group and joins it. */
    suspend fun createGroup(name: String): SyncPlayGroupSummary

    /** `POST /SyncPlay/Join`. Confirmation arrives on the websocket. */
    suspend fun joinGroup(groupId: UUID)

    /** `POST /SyncPlay/Leave`. */
    suspend fun leaveGroup()

    // Membership handshake ----------------------------------------------------------------------

    /**
     * `POST /SyncPlay/Buffering` — moves the group to `Waiting`.
     *
     * @param at when this client observed the state, on the *server* clock.
     */
    suspend fun reportBuffering(
        at: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    )

    /** `POST /SyncPlay/Ready` — the counterpart to buffering. */
    suspend fun reportReady(
        at: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    )

    /** `POST /SyncPlay/Ping` — this client's measured latency, in milliseconds. */
    suspend fun reportPing(pingMillis: Long)

    /**
     * `POST /SyncPlay/SetIgnoreWait` — sent when the player detaches while membership survives, so a
     * backgrounded member never gates the group.
     */
    suspend fun setIgnoreWait(ignoreWait: Boolean)

    // Transport requests ------------------------------------------------------------------------

    /** `POST /SyncPlay/Pause`. */
    suspend fun requestPause()

    /** `POST /SyncPlay/Unpause` — the protocol's name for "play". */
    suspend fun requestUnpause()

    /** `POST /SyncPlay/Seek`. */
    suspend fun requestSeek(positionTicks: Long)

    /**
     * `POST /SyncPlay/NextItem`.
     *
     * @param playlistItemId the slot the *client* believes is playing; the server drops the request
     *   if the group has already moved on, which is what keeps races harmless.
     */
    suspend fun requestNextItem(playlistItemId: UUID)

    /** `POST /SyncPlay/PreviousItem`; [playlistItemId] guards races as in [requestNextItem]. */
    suspend fun requestPreviousItem(playlistItemId: UUID)

    /** `POST /SyncPlay/SetPlaylistItem`. */
    suspend fun setPlaylistItem(playlistItemId: UUID)

    // Queue administration ----------------------------------------------------------------------

    /**
     * `POST /SyncPlay/SetNewQueue` — takes *library item ids* and an index, not playlist-item ids:
     * the server mints the slots.
     */
    suspend fun setNewQueue(
        itemIds: List<UUID>,
        playingItemPosition: Int,
        startPositionTicks: Long,
    )

    /** `POST /SyncPlay/Queue` — append, or insert after the playing item, per [mode]. */
    suspend fun addToQueue(
        itemIds: List<UUID>,
        mode: SyncPlayQueueMode,
    )

    /** `POST /SyncPlay/MovePlaylistItem` — reorder one slot. */
    suspend fun movePlaylistItem(
        playlistItemId: UUID,
        newIndex: Int,
    )

    /** `POST /SyncPlay/RemoveFromPlaylist` — remove slots, or clear the queue outright. */
    suspend fun removeFromPlaylist(
        playlistItemIds: List<UUID>,
        clearPlaylist: Boolean = false,
        clearPlayingItem: Boolean = false,
    )

    /** `POST /SyncPlay/SetShuffleMode`. */
    suspend fun setShuffleMode(mode: SyncPlayShuffleMode)

    /** `POST /SyncPlay/SetRepeatMode`. */
    suspend fun setRepeatMode(mode: SyncPlayRepeatMode)

    // Clock -------------------------------------------------------------------------------------

    /**
     * `GET /GetUtcTime`, one NTP exchange. The device-side timestamps must bracket the call as
     * tightly as possible — the delay estimate depends on it.
     */
    suspend fun sampleServerTime(): TimeSyncSample
}
