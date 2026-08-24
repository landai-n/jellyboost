package dev.jellyboost.player.syncplay.api

import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyboost.player.syncplay.model.TimeSyncSample
import java.time.Instant
import java.util.UUID

/**
 * Every SyncPlay REST call the app makes, behind one seam — the same reason `PlayerApi` exists:
 * the SDK's `ApiClient` is an abstract class whose operation objects come from extension
 * properties, which makes the controller untestable if it talks to it directly.
 *
 * This interface speaks **domain models and [Instant] only**. The SDK's DTOs and its local
 * wall-clock `LocalDateTime` stop at `SdkSyncPlayApi` /
 * `dev.jellyboost.player.syncplay.SyncPlayDtoMapping`.
 *
 * Naming follows the protocol's own split: `request*` calls *ask the server* to do something and
 * take effect only when the server broadcasts the matching command back, `report*` calls tell the
 * server about this client, and the rest is group/queue administration.
 */
@Suppress("TooManyFunctions") // The SyncPlay protocol has 22 operations; a partial facade is worse.
internal interface SyncPlayApi {
    // Group membership --------------------------------------------------------------------------

    /** `GET /SyncPlay/List` — groups on this server the user may join. */
    suspend fun getGroups(): List<SyncPlayGroupSummary>

    /** `POST /SyncPlay/New` — creates a group and joins it; returns the group as created. */
    suspend fun createGroup(name: String): SyncPlayGroupSummary

    /** `POST /SyncPlay/Join` — joins an existing group. Confirmation arrives on the websocket. */
    suspend fun joinGroup(groupId: UUID)

    /** `POST /SyncPlay/Leave` — leaves whatever group this session is in. */
    suspend fun leaveGroup()

    // Membership handshake ----------------------------------------------------------------------

    /**
     * `POST /SyncPlay/Buffering` — "I am not ready yet"; moves the group to `Waiting`.
     *
     * @param at the moment this client observed the state, on the *server* clock.
     */
    suspend fun reportBuffering(
        at: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    )

    /** `POST /SyncPlay/Ready` — "I am prepared at [positionTicks]"; the counterpart to buffering. */
    suspend fun reportReady(
        at: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    )

    /** `POST /SyncPlay/Ping` — this client's measured latency, in milliseconds. */
    suspend fun reportPing(pingMillis: Long)

    /**
     * `POST /SyncPlay/SetIgnoreWait` — asks the group to stop waiting on this client.
     *
     * Sent when the player detaches while membership survives, so a backgrounded member never
     * gates everyone else.
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
     * `POST /SyncPlay/NextItem` — advance the group's queue.
     *
     * @param playlistItemId the slot the *client* believes is playing; the server ignores the
     *   request if the group has already moved on, which is what keeps races harmless.
     */
    suspend fun requestNextItem(playlistItemId: UUID)

    /** `POST /SyncPlay/PreviousItem`; [playlistItemId] guards against races as in [requestNextItem]. */
    suspend fun requestPreviousItem(playlistItemId: UUID)

    /** `POST /SyncPlay/SetPlaylistItem` — jump the group to a specific slot. */
    suspend fun setPlaylistItem(playlistItemId: UUID)

    // Queue administration ----------------------------------------------------------------------

    /**
     * `POST /SyncPlay/SetNewQueue` — replaces the group's queue.
     *
     * Takes *library item ids* and an index (not playlist-item ids): the server mints the slots.
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
     * `GET /GetUtcTime`, timestamped on both sides — one NTP exchange.
     *
     * The device-side timestamps are taken as tightly around the call as possible, because
     * everything the estimator can do about network delay depends on them bracketing exactly the
     * request.
     */
    suspend fun sampleServerTime(): TimeSyncSample
}
