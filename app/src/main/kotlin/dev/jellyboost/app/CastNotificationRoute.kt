package dev.jellyboost.app

import dev.jellyboost.player.cast.CastingItem

/** Where a Cast notification tap takes the app, decided without a `NavController` so it is testable. */
internal sealed interface CastNotificationRoute {
    /**
     * @property replacePlayer the player on top is for another item: it is replaced rather than
     *   stacked under the new one.
     */
    data class OpenPlayer(
        val itemId: String,
        val startPositionTicks: Long,
        val replacePlayer: Boolean,
    ) : CastNotificationRoute

    /** The app is brought forward exactly as the user left it. */
    data object StayPut : CastNotificationRoute

    data object Home : CastNotificationRoute
}

/**
 * - **Signed out**: nothing — the auth flow is not the place to open a film.
 * - **Something of ours is on the receiver** ([casting] is only non-`null` while no player screen is
 *   attached): its player, which reattaches to the receiver rather than reloading it — unless a player
 *   for that very item is already on top.
 * - **Nothing detached, but a player on top**: that player *is* the cast session's remote control (it
 *   is the attached screen), so it stays.
 * - **Nothing at all**: Home, as the notification's own contract says.
 *
 * @param playerItemId the item of the player route on top, or `null` when the top is not a player.
 */
internal fun castNotificationRoute(
    casting: CastingItem?,
    playerItemId: String?,
    signedIn: Boolean,
): CastNotificationRoute {
    val onPlayer = playerItemId != null
    return when {
        !signedIn -> CastNotificationRoute.StayPut
        casting == null -> if (onPlayer) CastNotificationRoute.StayPut else CastNotificationRoute.Home
        // Case-insensitive: a UUID differing only in case is the same UUID (see CastMetadataHolder).
        playerItemId.equals(casting.itemId, ignoreCase = true) -> CastNotificationRoute.StayPut
        else ->
            CastNotificationRoute.OpenPlayer(
                itemId = casting.itemId,
                startPositionTicks = casting.positionTicks,
                replacePlayer = onPlayer,
            )
    }
}
