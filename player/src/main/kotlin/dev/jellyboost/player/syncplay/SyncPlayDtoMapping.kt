package dev.jellyboost.player.syncplay

import dev.jellyboost.data.toSdkDateTime
import dev.jellyboost.data.toSdkInstant
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

// **The one and only place SyncPlay converts between SDK DTOs and the domain models.**
//
// In particular it is the one place a `java.time.LocalDateTime` is allowed to exist. SDK 1.8.12's
// `DateTimeSerializer` reads and writes those as **local wall-clock** time in
// [ZoneId.systemDefault] — not UTC — so `SendCommand.when` parsed as if it were UTC is wrong by the
// device's offset, which for SyncPlay means every scheduled play/pause/seek fires hours away from
// the rest of the group. (Same bug class as the M4 two-hour progress-report shift; see
// `dev.jellyboost.data.toSdkInstant`, whose helpers do the conversion.)
//
// Every function here takes an explicit [zone] so the round-trip can be tested against a fixed
// non-UTC zone: a test that pins `Europe/Paris` fails the moment someone "simplifies" this to
// `toInstant(ZoneOffset.UTC)`.
//
// Functions are top-level and pure — no client, no coroutines — so the socket mapping is testable
// without a live socket. The enum tables live next door in `SyncPlayEnumMapping.kt`; nothing there
// touches a timestamp, and keeping this file small is what makes the rule above enforceable.

// SDK -> domain ---------------------------------------------------------------------------------

/** `GroupInfoDto` as the group list and the join flow see it. */
internal fun GroupInfoDto.toSummary(zone: ZoneId = ZoneId.systemDefault()): SyncPlayGroupSummary =
    SyncPlayGroupSummary(
        id = groupId,
        name = groupName,
        participants = participants,
        state = state.toDomain(),
        lastUpdatedAt = lastUpdatedAt.toSdkInstant(zone),
    )

/** `SendCommand` — the broadcast transport command. [SendCommand.when] crosses the time boundary here. */
internal fun SendCommand.toDomain(zone: ZoneId = ZoneId.systemDefault()): SyncPlayCommand =
    SyncPlayCommand(
        type = command.toDomain(),
        whenInstant = `when`.toSdkInstant(zone),
        positionTicks = positionTicks,
        playlistItemId = playlistItemId,
        emittedAt = emittedAt.toSdkInstant(zone),
    )

/** `PlayQueueUpdate` — the group's queue, playing slot and shuffle/repeat modes. */
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

/**
 * `GroupUpdate` -> the domain event.
 *
 * `GroupUpdate` is a **sealed** interface in the SDK, so this `when` is exhaustive by construction:
 * a server-side addition cannot reach us without an SDK bump, and that bump breaks this file at
 * compile time rather than silently dropping an update at runtime.
 */
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
 * `UtcTimeResponse` plus the two device-side timestamps into one NTP sample.
 *
 * The server's two readings are wall-clock in the *device's* zone once the SDK has deserialized
 * them, so they go through [toSdkInstant] like every other SDK date — skipping that is what would
 * make the estimated offset come out as the UTC offset instead of the true clock skew.
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

/**
 * The domain instant as the wall-clock reading the SDK will serialize.
 *
 * Only `BufferRequestDto`/`ReadyRequestDto` need it today; it exists so that outbound conversion is
 * as single-sourced as the inbound one.
 */
internal fun Instant.toSdkWallClock(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = toSdkDateTime(zone)
