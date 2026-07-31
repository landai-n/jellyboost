package dev.jellyboost.player.syncplay

import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayRequestKind
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import org.jellyfin.sdk.model.api.GroupQueueMode
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.PlaybackRequestType
import org.jellyfin.sdk.model.api.SendCommandType

// The SyncPlay enum vocabulary, translated both ways.
//
// Split out of `SyncPlayDtoMapping` because none of it touches a timestamp: keeping the file that
// owns the `LocalDateTime` boundary small is the point of that file, and a wall of one-to-one
// `when` tables buries it.
//
// Every table is exhaustive with no `else`, so an SDK bump that adds a value fails the build here
// instead of silently mapping to a wrong-but-plausible neighbour.

// SDK -> domain ---------------------------------------------------------------------------------

internal fun GroupStateType.toDomain(): SyncPlayGroupState =
    when (this) {
        GroupStateType.IDLE -> SyncPlayGroupState.Idle
        GroupStateType.WAITING -> SyncPlayGroupState.Waiting
        GroupStateType.PAUSED -> SyncPlayGroupState.Paused
        GroupStateType.PLAYING -> SyncPlayGroupState.Playing
    }

internal fun SendCommandType.toDomain(): SyncPlayCommandType =
    when (this) {
        SendCommandType.UNPAUSE -> SyncPlayCommandType.Unpause
        SendCommandType.PAUSE -> SyncPlayCommandType.Pause
        SendCommandType.STOP -> SyncPlayCommandType.Stop
        SendCommandType.SEEK -> SyncPlayCommandType.Seek
    }

internal fun GroupShuffleMode.toDomain(): SyncPlayShuffleMode =
    when (this) {
        GroupShuffleMode.SORTED -> SyncPlayShuffleMode.Sorted
        GroupShuffleMode.SHUFFLE -> SyncPlayShuffleMode.Shuffle
    }

internal fun GroupRepeatMode.toDomain(): SyncPlayRepeatMode =
    when (this) {
        GroupRepeatMode.REPEAT_NONE -> SyncPlayRepeatMode.None
        GroupRepeatMode.REPEAT_ONE -> SyncPlayRepeatMode.One
        GroupRepeatMode.REPEAT_ALL -> SyncPlayRepeatMode.All
    }

internal fun PlayQueueUpdateReason.toDomain(): SyncPlayQueueUpdateReason =
    when (this) {
        PlayQueueUpdateReason.NEW_PLAYLIST -> SyncPlayQueueUpdateReason.NewPlaylist
        PlayQueueUpdateReason.SET_CURRENT_ITEM -> SyncPlayQueueUpdateReason.SetCurrentItem
        PlayQueueUpdateReason.REMOVE_ITEMS -> SyncPlayQueueUpdateReason.RemoveItems
        PlayQueueUpdateReason.MOVE_ITEM -> SyncPlayQueueUpdateReason.MoveItem
        PlayQueueUpdateReason.QUEUE -> SyncPlayQueueUpdateReason.Queue
        PlayQueueUpdateReason.QUEUE_NEXT -> SyncPlayQueueUpdateReason.QueueNext
        PlayQueueUpdateReason.NEXT_ITEM -> SyncPlayQueueUpdateReason.NextItem
        PlayQueueUpdateReason.PREVIOUS_ITEM -> SyncPlayQueueUpdateReason.PreviousItem
        PlayQueueUpdateReason.REPEAT_MODE -> SyncPlayQueueUpdateReason.RepeatMode
        PlayQueueUpdateReason.SHUFFLE_MODE -> SyncPlayQueueUpdateReason.ShuffleMode
    }

@Suppress("CyclomaticComplexMethod") // A 17-entry one-to-one table; splitting it would only hide it.
internal fun PlaybackRequestType.toDomain(): SyncPlayRequestKind =
    when (this) {
        PlaybackRequestType.PLAY -> SyncPlayRequestKind.Play
        PlaybackRequestType.SET_PLAYLIST_ITEM -> SyncPlayRequestKind.SetPlaylistItem
        PlaybackRequestType.REMOVE_FROM_PLAYLIST -> SyncPlayRequestKind.RemoveFromPlaylist
        PlaybackRequestType.MOVE_PLAYLIST_ITEM -> SyncPlayRequestKind.MovePlaylistItem
        PlaybackRequestType.QUEUE -> SyncPlayRequestKind.Queue
        PlaybackRequestType.UNPAUSE -> SyncPlayRequestKind.Unpause
        PlaybackRequestType.PAUSE -> SyncPlayRequestKind.Pause
        PlaybackRequestType.STOP -> SyncPlayRequestKind.Stop
        PlaybackRequestType.SEEK -> SyncPlayRequestKind.Seek
        PlaybackRequestType.BUFFER -> SyncPlayRequestKind.Buffer
        PlaybackRequestType.READY -> SyncPlayRequestKind.Ready
        PlaybackRequestType.NEXT_ITEM -> SyncPlayRequestKind.NextItem
        PlaybackRequestType.PREVIOUS_ITEM -> SyncPlayRequestKind.PreviousItem
        PlaybackRequestType.SET_REPEAT_MODE -> SyncPlayRequestKind.SetRepeatMode
        PlaybackRequestType.SET_SHUFFLE_MODE -> SyncPlayRequestKind.SetShuffleMode
        PlaybackRequestType.PING -> SyncPlayRequestKind.Ping
        PlaybackRequestType.IGNORE_WAIT -> SyncPlayRequestKind.IgnoreWait
    }

// Domain -> SDK ---------------------------------------------------------------------------------

internal fun SyncPlayQueueMode.toSdk(): GroupQueueMode =
    when (this) {
        SyncPlayQueueMode.Queue -> GroupQueueMode.QUEUE
        SyncPlayQueueMode.QueueNext -> GroupQueueMode.QUEUE_NEXT
    }

internal fun SyncPlayShuffleMode.toSdk(): GroupShuffleMode =
    when (this) {
        SyncPlayShuffleMode.Sorted -> GroupShuffleMode.SORTED
        SyncPlayShuffleMode.Shuffle -> GroupShuffleMode.SHUFFLE
    }

internal fun SyncPlayRepeatMode.toSdk(): GroupRepeatMode =
    when (this) {
        SyncPlayRepeatMode.None -> GroupRepeatMode.REPEAT_NONE
        SyncPlayRepeatMode.One -> GroupRepeatMode.REPEAT_ONE
        SyncPlayRepeatMode.All -> GroupRepeatMode.REPEAT_ALL
    }
