package dev.jellyboost.data.userdata

import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.toSdkDateTime
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto

/**
 * The wire half of user-data writes: the three requests a `user_data` row can be asserted with, and
 * the order they have to go in.
 *
 * Both `UserDataRepositoryImpl` (one endpoint per operation) and `UserDataSyncer` (all three in
 * sequence) need these calls, so the **one rule that is not obvious** is stated here once, for
 * both:
 *
 * > `markPlayedItem` **clears the server's resume position**. Anything asserting a position
 * > alongside a played state must therefore send the position *after* it, never before.
 *
 * ### Which caller sends what, and why that is not a third spelling
 * - [pushUserData] asserts the **whole row** through all three requests, in that order. It is what
 *   `UserDataSyncer` uses, because a pending row may hold several operations batched by an offline
 *   session and the worker cannot know which produced it.
 * - A single operation the app *did* originate sends only its own request:
 *   [pushPlayedState] for `setPlayed`, [pushFavoriteState] for `setFavorite`, [pushFullState] for
 *   `setPosition`. The dedicated played/favourite endpoints carry server-side side effects (the
 *   play count) that the merge endpoint does not, which is why they exist at all.
 *
 * `setPlayed` therefore does not re-assert the position — and does not need to. Its local edit
 * mirrors the server's clearing (`playbackPositionTicks = 0` when played), so the two agree without
 * a second request. That is the invariant above, satisfied by construction rather than by ordering.
 * The alternative — routing every operation through [pushUserData] — was measured against the one
 * caller that would pay for it: `PlaybackReporter` calls `setPosition` every five seconds, and it
 * would turn each tick into three requests, one of which *clears the server's position* before the
 * next restores it.
 */
internal suspend fun ApiClient.pushUserData(row: UserDataEntity) {
    pushPlayedState(row)
    pushFavoriteState(row)
    // Last, per the rule at the top of this file.
    pushFullState(row)
}

/** Asserts `played` through the dedicated endpoints — the ones that also move the play count. */
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

/** Asserts `isFavorite` through the dedicated endpoints. */
internal suspend fun ApiClient.pushFavoriteState(row: UserDataEntity) {
    if (row.isFavorite) {
        userLibraryApi.markFavoriteItem(itemId = row.itemId, userId = row.userId)
    } else {
        userLibraryApi.unmarkFavoriteItem(itemId = row.itemId, userId = row.userId)
    }
}

/**
 * Asserts the item's **full** desired state through `updateItemUserData`.
 *
 * The whole state rather than the one field that changed: the endpoint merges what it is given, so
 * a partial DTO would risk resetting the rest.
 */
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
