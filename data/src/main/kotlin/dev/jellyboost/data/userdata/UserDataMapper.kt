package dev.jellyboost.data.userdata

import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.toSdkInstant
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Instant
import java.util.UUID

/**
 * `playedPercentage` and `playCount` are deliberately not persisted and come back at their defaults;
 * progress then falls back to `playbackPositionTicks / runTimeTicks`, the same number.
 */
internal fun UserDataEntity.toDomain(): UserData =
    UserData(
        played = played,
        isFavorite = isFavorite,
        playbackPositionTicks = playbackPositionTicks,
        lastPlayedDate = lastPlayedDate,
    )

/**
 * Only for a row that is **not** pending sync, hence `toBeSynced = false`: a copy of server state,
 * not a change the server owes us.
 *
 * The two timestamps are sourced differently on purpose. [UserDataEntity.lastPlayedDate] is the
 * server's value copied verbatim, `null` included — it is the *server* half of most-recent-wins, and
 * inventing one here would make an unplayed item look freshly watched.
 * [UserDataEntity.updatedAt] is [adoptedAt], the *local* half, which only decides anything for a
 * `toBeSynced = true` row this function never produces.
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
