package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.toSdkDateTime
import dev.jellyboost.core.network.toSdkInstant
import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.TimeSyncSample
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import org.jellyfin.sdk.model.api.UtcTimeResponse
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// **The one place a `java.time.LocalDateTime` may exist in SyncPlay.** SDK 1.8.12's `DateTimeSerializer` reads
// and writes them as local wall-clock time in [ZoneId.systemDefault], not UTC, so a `SendCommand.when` treated
// as UTC is wrong by the device's offset and every scheduled play/pause/seek fires hours off the group.
//
// The explicit [zone] parameters exist so the round trip can be pinned against a non-UTC zone; "simplifying"
// them to `toInstant(ZoneOffset.UTC)` is the failure this guards.

// SDK -> domain ---------------------------------------------------------------------------------

internal fun GroupInfoDto.toSummary(zone: ZoneId = ZoneId.systemDefault()): SyncPlayGroupSummary =
    SyncPlayGroupSummary(
        id = groupId,
        name = groupName,
        participants = participants,
        state = state.toDomain(),
        lastUpdatedAt = lastUpdatedAt.toSdkInstant(zone),
    )

/** [SendCommand.when] crosses the time boundary here. */
internal fun SendCommand.toDomain(zone: ZoneId = ZoneId.systemDefault()): SyncPlayCommand =
    SyncPlayCommand(
        type = command.toDomain(),
        whenInstant = `when`.toSdkInstant(zone),
        positionTicks = positionTicks,
        playlistItemId = playlistItemId,
        emittedAt = emittedAt.toSdkInstant(zone),
    )

internal fun PlayQueueUpdate.toDomain(zone: ZoneId = ZoneId.systemDefault()): SyncPlayGroupQueue =
    SyncPlayGroupQueue(
        entries = playlist.map { SyncPlayQueueEntry(itemId = it.itemId, playlistItemId = it.playlistItemId) },
        playingItemIndex = playingItemIndex,
        startPositionTicks = startPositionTicks,
        isPlaying = isPlaying,
        shuffleMode = shuffleMode.toDomain(),
        repeatMode = repeatMode.toDomain(),
        reason = reason.toDomain(),
        lastUpdate = lastUpdate.toSdkInstant(zone),
    )

/** `GroupUpdate` is sealed in the SDK, so an SDK bump breaks this `when` rather than silently dropping updates. */
internal fun GroupUpdate.toEvent(zone: ZoneId = ZoneId.systemDefault()): SyncPlayGroupEvent =
    when (this) {
        is SyncPlayGroupJoinedUpdate -> SyncPlayGroupEvent.Joined(data.toSummary(zone))
        is SyncPlayGroupLeftUpdate -> SyncPlayGroupEvent.Left(groupId)
        is SyncPlayStateUpdate ->
            SyncPlayGroupEvent.StateChanged(
                state = data.state.toDomain(),
                reason = data.reason.toDomain(),
            )

        is SyncPlayPlayQueueUpdate -> SyncPlayGroupEvent.QueueChanged(data.toDomain(zone))
        is SyncPlayUserJoinedUpdate -> SyncPlayGroupEvent.UserJoined(data)
        is SyncPlayUserLeftUpdate -> SyncPlayGroupEvent.UserLeft(data)
        is SyncPlayNotInGroupUpdate -> SyncPlayGroupEvent.NotInGroup
        is SyncPlayGroupDoesNotExistUpdate -> SyncPlayGroupEvent.GroupGone
        is SyncPlayLibraryAccessDeniedUpdate -> SyncPlayGroupEvent.LibraryAccessDenied
    }

/**
 * The server's two readings deserialize as wall-clock in the *device's* zone, so they must go through
 * [toSdkInstant]: skipping it yields the UTC offset instead of the true clock skew.
 */
internal fun UtcTimeResponse.toSample(
    requestSent: Instant,
    responseReceived: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): TimeSyncSample =
    TimeSyncSample(
        requestSent = requestSent,
        serverReceived = requestReceptionTime.toSdkInstant(zone),
        serverSent = responseTransmissionTime.toSdkInstant(zone),
        responseReceived = responseReceived,
    )

// Domain -> SDK ---------------------------------------------------------------------------------

/** The domain instant as the wall-clock reading the SDK will serialize. */
internal fun Instant.toSdkWallClock(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = toSdkDateTime(zone)
