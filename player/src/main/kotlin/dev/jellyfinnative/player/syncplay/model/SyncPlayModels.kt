package dev.jellyfinnative.player.syncplay.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

// The SyncPlay domain vocabulary — everything above the SDK boundary speaks these types.
//
// Two rules hold for this file:
//
// 1. No SDK types. The `org.jellyfin.sdk.model.api` DTOs stop at
//    `dev.jellyfinnative.player.syncplay.SyncPlayDtoMapping`.
// 2. No `LocalDateTime`. The SDK's date fields are *local wall-clock* readings (see
//    `dev.jellyfinnative.data.toSdkInstant`); the moment one escapes the mapping boundary every
//    scheduled command is off by the device's UTC offset. Timestamps here are `Instant`, always.

/** Where the server thinks the group is, `GroupStateType`. */
enum class SyncPlayGroupState {
    /** No queue set, or playback finished. */
    Idle,

    /** The group is waiting for members to buffer/ready up before it resumes. */
    Waiting,

    Paused,
    Playing,
}

/** What the server told us to do, `SendCommandType`. */
enum class SyncPlayCommandType {
    /** The SDK's name for "play" — the group leaves the paused/waiting state. */
    Unpause,

    Pause,
    Stop,
    Seek,
}

/** `GroupShuffleMode`. */
enum class SyncPlayShuffleMode { Sorted, Shuffle }

/** `GroupRepeatMode`. */
enum class SyncPlayRepeatMode { None, One, All }

/** Where new items land when added to a group queue, `GroupQueueMode`. */
enum class SyncPlayQueueMode {
    /** Append to the end of the queue. */
    Queue,

    /** Insert directly after the item playing now. */
    QueueNext,
}

/** Why the server re-sent the queue, `PlayQueueUpdateReason`. */
enum class SyncPlayQueueUpdateReason {
    NewPlaylist,
    SetCurrentItem,
    RemoveItems,
    MoveItem,
    Queue,
    QueueNext,
    NextItem,
    PreviousItem,
    RepeatMode,
    ShuffleMode,
}

/**
 * Which member request moved the group into its current state, `PlaybackRequestType`.
 *
 * Mirrored in full rather than collapsed: a `Waiting` caused by `Buffer` (someone is loading) and
 * one caused by `Seek` (everyone is repositioning) look identical without it, and re-widening the
 * mapping boundary later is exactly what this seam exists to avoid.
 */
enum class SyncPlayRequestKind {
    Play,
    SetPlaylistItem,
    RemoveFromPlaylist,
    MovePlaylistItem,
    Queue,
    Unpause,
    Pause,
    Stop,
    Seek,
    Buffer,
    Ready,
    NextItem,
    PreviousItem,
    SetRepeatMode,
    SetShuffleMode,
    Ping,
    IgnoreWait,
}

/** One group as the group list and the join flow see it, `GroupInfoDto`. */
data class SyncPlayGroupSummary(
    val id: UUID,
    val name: String,
    val participants: List<String>,
    val state: SyncPlayGroupState,
    val lastUpdatedAt: Instant,
)

/**
 * One slot in a group queue.
 *
 * [itemId] is the library item — what the resolver turns into a stream or a downloaded file.
 * [playlistItemId] is the *slot*, and it is what every SyncPlay request identifies: the same
 * episode queued twice is two playlist items, and a command naming only [itemId] would be
 * ambiguous.
 */
data class SyncPlayQueueEntry(
    val itemId: UUID,
    val playlistItemId: UUID,
)

/**
 * A group's queue as of the last `PlayQueueUpdate`.
 *
 * The server identifies the playing slot by *index*, not by playlist-item id (verified against SDK
 * 1.8.12's `PlayQueueUpdate`); [playingEntry] is the convenience the rest of the app actually wants.
 */
data class SyncPlayGroupQueue(
    val entries: List<SyncPlayQueueEntry>,
    val playingItemIndex: Int,
    val startPositionTicks: Long,
    val isPlaying: Boolean,
    val shuffleMode: SyncPlayShuffleMode,
    val repeatMode: SyncPlayRepeatMode,
    val reason: SyncPlayQueueUpdateReason,
    val lastUpdate: Instant,
) {
    /** The slot the group is on, or `null` when the index points outside the queue (empty queue). */
    val playingEntry: SyncPlayQueueEntry?
        get() = entries.getOrNull(playingItemIndex)
}

/**
 * A transport command the server broadcast to the group, `SendCommand`.
 *
 * [whenInstant] is the *server-clock* moment the command takes effect — usually a little in the
 * future, which is what lets every member act at the same wall-clock instant. Converting it to a
 * local instant is the job of the time-sync offset, not of this model.
 */
data class SyncPlayCommand(
    val type: SyncPlayCommandType,
    val whenInstant: Instant,
    val positionTicks: Long?,
    val playlistItemId: UUID,
    val emittedAt: Instant,
)

/** Everything the group websocket can tell us, `GroupUpdate` and its subtypes. */
sealed interface SyncPlayGroupEvent {
    /** We are in the group now; carries its full state. */
    data class Joined(
        val group: SyncPlayGroupSummary,
    ) : SyncPlayGroupEvent

    /** We left the group (our own leave, or the server removed us); carries the group id. */
    data class Left(
        val groupId: UUID,
    ) : SyncPlayGroupEvent

    /** The group moved between idle/waiting/paused/playing. */
    data class StateChanged(
        val state: SyncPlayGroupState,
        val reason: SyncPlayRequestKind,
    ) : SyncPlayGroupEvent

    /** The queue changed — new playlist, reorder, removal, next/previous, shuffle or repeat. */
    data class QueueChanged(
        val queue: SyncPlayGroupQueue,
    ) : SyncPlayGroupEvent

    /** Someone else joined; [name] is the user's display name. */
    data class UserJoined(
        val name: String,
    ) : SyncPlayGroupEvent

    /** Someone else left; [name] is the user's display name. */
    data class UserLeft(
        val name: String,
    ) : SyncPlayGroupEvent

    /** The server rejected a request because this session is not in a group. */
    data object NotInGroup : SyncPlayGroupEvent

    /** The group we asked about no longer exists. */
    data object GroupGone : SyncPlayGroupEvent

    /** The group is playing something this account may not see. */
    data object LibraryAccessDenied : SyncPlayGroupEvent
}

/**
 * One NTP-style exchange with `GET /GetUtcTime`.
 *
 * The four timestamps are the classic t0/t1/t2/t3: [requestSent] and [responseReceived] come from
 * the device clock, [serverReceived] and [serverSent] from the server's — so the pair difference
 * cancels the network delay out of the offset.
 */
data class TimeSyncSample(
    val requestSent: Instant,
    val serverReceived: Instant,
    val serverSent: Instant,
    val responseReceived: Instant,
) {
    /** Network round-trip with the server's own processing time removed: (t3−t0) − (t2−t1). */
    val roundTrip: Duration
        get() =
            Duration
                .between(requestSent, responseReceived)
                .minus(Duration.between(serverReceived, serverSent))

    /** How far the server clock runs ahead of this device's: ((t1−t0) + (t2−t3)) / 2. */
    val offset: Duration
        get() =
            Duration
                .between(requestSent, serverReceived)
                .plus(Duration.between(responseReceived, serverSent))
                .dividedBy(2)
}
