package dev.jellyboost.player.syncplay.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

// Two rules hold for this file:
// 1. No SDK types — the `org.jellyfin.sdk.model.api` DTOs stop at `SyncPlayDtoMapping`.
// 2. No `LocalDateTime`. The SDK's date fields are *local wall-clock* readings; one escaping the
//    mapping boundary puts every scheduled command off by the device's UTC offset. Always `Instant`.

/** `GroupStateType`. */
enum class SyncPlayGroupState {
    /** No queue set, or playback finished. */
    Idle,

    /** Waiting for members to buffer/ready up before resuming. */
    Waiting,

    Paused,
    Playing,
}

/** `SendCommandType`. */
internal enum class SyncPlayCommandType {
    /** The SDK's name for "play". */
    Unpause,

    Pause,
    Stop,
    Seek,
}

/** `GroupShuffleMode`. */
internal enum class SyncPlayShuffleMode { Sorted, Shuffle }

/** `GroupRepeatMode`. */
internal enum class SyncPlayRepeatMode { None, One, All }

/** `GroupQueueMode`. */
internal enum class SyncPlayQueueMode {
    /** Append to the end of the queue. */
    Queue,

    /** Insert directly after the item playing now. */
    QueueNext,
}

/** `PlayQueueUpdateReason`. */
internal enum class SyncPlayQueueUpdateReason {
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
 * `PlaybackRequestType`. Mirrored in full: without it a `Waiting` caused by `Buffer` and one caused
 * by `Seek` are indistinguishable.
 */
internal enum class SyncPlayRequestKind {
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

/** `GroupInfoDto`. */
data class SyncPlayGroupSummary(
    val id: UUID,
    val name: String,
    val participants: List<String>,
    val state: SyncPlayGroupState,
    val lastUpdatedAt: Instant,
)

/**
 * Every SyncPlay request identifies the *slot* ([playlistItemId]), never [itemId]: the same episode
 * queued twice is two playlist items.
 */
internal data class SyncPlayQueueEntry(
    val itemId: UUID,
    val playlistItemId: UUID,
)

/**
 * The server identifies the playing slot by *index*, not by playlist-item id (verified against SDK
 * 1.8.12's `PlayQueueUpdate`).
 */
internal data class SyncPlayGroupQueue(
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

    /**
     * `true` when another slot follows, or a repeat mode will bring one back. The player screen's
     * "the film ended, close me" rule reads it: popping would close the player the group is filling.
     */
    val hasFollowingEntry: Boolean
        get() = playingItemIndex < entries.lastIndex || (repeatMode != SyncPlayRepeatMode.None && entries.isNotEmpty())
}

/**
 * `SendCommand`. [whenInstant] is on the *server* clock — usually slightly in the future so every
 * member acts at the same wall-clock instant; the time-sync offset converts it, not this model.
 */
internal data class SyncPlayCommand(
    val type: SyncPlayCommandType,
    val whenInstant: Instant,
    val positionTicks: Long?,
    val playlistItemId: UUID,
    val emittedAt: Instant,
)

/** `GroupUpdate` and its subtypes. */
internal sealed interface SyncPlayGroupEvent {
    data class Joined(
        val group: SyncPlayGroupSummary,
    ) : SyncPlayGroupEvent

    /** Our own leave, or the server removed us. */
    data class Left(
        val groupId: UUID,
    ) : SyncPlayGroupEvent

    data class StateChanged(
        val state: SyncPlayGroupState,
        val reason: SyncPlayRequestKind,
    ) : SyncPlayGroupEvent

    data class QueueChanged(
        val queue: SyncPlayGroupQueue,
    ) : SyncPlayGroupEvent

    /** [name] is another user's display name. */
    data class UserJoined(
        val name: String,
    ) : SyncPlayGroupEvent

    /** [name] is another user's display name. */
    data class UserLeft(
        val name: String,
    ) : SyncPlayGroupEvent

    /** The server rejected a request because this session is not in a group. */
    data object NotInGroup : SyncPlayGroupEvent

    data object GroupGone : SyncPlayGroupEvent

    data object LibraryAccessDenied : SyncPlayGroupEvent
}

/**
 * One NTP-style exchange with `GET /GetUtcTime`: t0/t1/t2/t3, where [requestSent] and
 * [responseReceived] are device-clock and [serverReceived]/[serverSent] are server-clock.
 */
internal data class TimeSyncSample(
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
