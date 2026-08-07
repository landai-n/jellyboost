package dev.jellyboost.data.userdata

import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.toSdkInstant
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Instant
import java.util.UUID

/**
 * Maps a stored row onto the domain model the UI sees.
 *
 * `playedPercentage` and `playCount` are deliberately not persisted (docs/PLAN.md lists exactly
 * six columns for this table), so they come back at their defaults: progress then falls back to
 * `playbackPositionTicks / runTimeTicks`, which is the same number the server's percentage would
 * have produced.
 */
internal fun UserDataEntity.toDomain(): UserData =
    UserData(
        played = played,
        isFavorite = isFavorite,
        playbackPositionTicks = playbackPositionTicks,
        lastPlayedDate = lastPlayedDate,
    )

/**
 * Maps the `userData` block of a server read onto a row the local mirror can adopt.
 *
 * Only ever used for a row that is **not** pending sync (see `BrowseCacheWriter`), hence
 * `toBeSynced = false`: this row is a copy of server state, not a change the server owes us.
 *
 * The two timestamps mean different things and are deliberately sourced differently:
 *
 * - [UserDataEntity.lastPlayedDate] is the server's own value, copied verbatim (`null` included).
 *   It is the *server* half of M8's most-recent-wins comparison, so inventing one here — say, the
 *   time of the read — would make an unplayed item look freshly watched.
 * - [UserDataEntity.updatedAt] is [adoptedAt], the moment this device learned the server's state.
 *   It is the *local* half of that comparison, and it only ever decides anything for a
 *   `toBeSynced = true` row, which this function never produces: the sync worker's work list is
 *   `toBeSynced = 1`, and any later local write re-stamps `updatedAt` from the clock anyway. So it
 *   records when the mirror was refreshed without ever claiming the local row is newer than the
 *   server's.
 */
internal fun UserItemDataDto.toEntity(
    itemId: UUID,
    userId: UUID,
    adoptedAt: Instant,
): UserDataEntity =
    UserDataEntity(
        itemId = itemId,
        userId = userId,
        played = played,
        isFavorite = isFavorite,
        playbackPositionTicks = playbackPositionTicks,
        lastPlayedDate = lastPlayedDate?.toSdkInstant(),
        toBeSynced = false,
        updatedAt = adoptedAt,
    )
