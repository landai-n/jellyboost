package dev.jellyfinnative.data.userdata

import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.core.database.entities.UserDataEntity

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
