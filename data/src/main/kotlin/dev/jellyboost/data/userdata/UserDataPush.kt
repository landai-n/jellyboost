package dev.jellyboost.data.userdata

import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.toSdkDateTime
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto

/**
 * > `markPlayedItem` **clears the server's resume position**. Anything asserting a position
 * > alongside a played state must send the position *after* it, never before.
 *
 * [pushUserData] asserts the whole row in that order — what `UserDataSyncer` needs, since a pending
 * row may batch several operations from an offline session. A single app-originated operation sends
 * only its own request; the dedicated played/favourite endpoints carry server-side side effects (the
 * play count) that the merge endpoint does not.
 *
 * Routing everything through [pushUserData] instead would turn each of `PlaybackReporter`'s
 * five-second `setPosition` ticks into three requests, one of which clears the position the next
 * restores.
 */
internal suspend fun ApiClient.pushUserData(row: UserDataEntity) {
    pushPlayedState(row)
    pushFavoriteState(row)
    // Last, per the rule at the top of this file.
    pushFullState(row)
}

/** The dedicated endpoints — the ones that also move the play count. */
internal suspend fun ApiClient.pushPlayedState(row: UserDataEntity) {
    if (row.played) {
        playStateApi.markPlayedItem(
            itemId = row.itemId,
            userId = row.userId,
            datePlayed = row.lastPlayedDate?.toSdkDateTime(),
        )
    } else {
        playStateApi.markUnplayedItem(itemId = row.itemId, userId = row.userId)
    }
}

internal suspend fun ApiClient.pushFavoriteState(row: UserDataEntity) {
    if (row.isFavorite) {
        userLibraryApi.markFavoriteItem(itemId = row.itemId, userId = row.userId)
    } else {
        userLibraryApi.unmarkFavoriteItem(itemId = row.itemId, userId = row.userId)
    }
}

/** The **whole** state, not the changed field: the endpoint merges, so a partial DTO resets the rest. */
internal suspend fun ApiClient.pushFullState(row: UserDataEntity) {
    itemsApi.updateItemUserData(
        itemId = row.itemId,
        userId = row.userId,
        data =
            UpdateUserItemDataDto(
                playbackPositionTicks = row.playbackPositionTicks,
                played = row.played,
                isFavorite = row.isFavorite,
                lastPlayedDate = row.lastPlayedDate?.toSdkDateTime(),
            ),
    )
}
