package dev.jellyfinnative.player.ui

import androidx.lifecycle.SavedStateHandle
import dev.jellyfinnative.player.model.PlaybackSnapshot

/**
 * The player route's arguments, and the live position written back over them for a process death.
 *
 * Extracted from `PlayerViewModel` (audit ARCH-10). The handle carries two quite different things
 * under one type — what the user tapped (`ARG_`), which is read-only and must stay intact, and what
 * the session had reached (`KEY_`), which is written every five seconds — and keeping the rule
 * straight is worth a class of its own.
 *
 * Constructed by the ViewModel rather than injected: it is a view over the ViewModel's own
 * `SavedStateHandle`, so a Hilt binding would only be a longer way to hand it the same object.
 */
internal class PlayerSessionStore(
    private val handle: SavedStateHandle,
) {
    val itemId: String =
        requireNotNull(handle.get<String>(PlayerViewModel.ARG_ITEM_ID)) {
            "Player route is missing its '${PlayerViewModel.ARG_ITEM_ID}' argument"
        }

    val mediaSourceId: String? = handle[PlayerViewModel.ARG_MEDIA_SOURCE_ID]

    /**
     * Where the last session had actually got to, or `null` if this is a fresh navigation.
     *
     * Non-null only after a process death: the handle is restored with whatever the last progress
     * tick wrote into it ([rememberLivePosition]). It is the difference between coming back to the
     * film where the user left it and coming back to where they *tapped Play* — and the latter is
     * not merely a cosmetic annoyance, because the progress reporter would then stamp that stale
     * position with a fresh timestamp and most-recent-wins sync would push it out to the server and
     * to every other device.
     */
    private val restoredPositionTicks: Long? = handle[PlayerViewModel.KEY_LIVE_POSITION_TICKS]

    val startPositionTicks: Long =
        restoredPositionTicks ?: handle[PlayerViewModel.ARG_START_TICKS] ?: 0L

    /**
     * Whether the session should start playing.
     *
     * A fresh tap on Play means play. A restore resumes only what was actually running: an item the
     * user had paused before leaving the app must not start talking to an empty room minutes later.
     */
    val playWhenReady: Boolean =
        restoredPositionTicks == null || handle.get<Boolean>(PlayerViewModel.KEY_WAS_PLAYING) == true

    /**
     * Writes the live position back into the handle the system restores after a process death.
     *
     * Without this the handle only ever holds the navigation arguments, so a restored back stack
     * re-opens the player at the position the item had when Play was *tapped* — and the next
     * progress tick then writes that stale position to the local user-data row with a fresh
     * timestamp, which most-recent-wins sync happily propagates onwards. Losing the resume point of
     * a film someone is halfway through is silent, permanent and entirely invisible until they come
     * back to it.
     *
     * Position 0 is not written: it is indistinguishable from "no session yet", and falling back to
     * the navigation argument is the better answer for it anyway.
     */
    fun rememberLivePosition(snapshot: PlaybackSnapshot) {
        if (snapshot.positionTicks <= 0L) return
        handle[PlayerViewModel.KEY_LIVE_POSITION_TICKS] = snapshot.positionTicks
        handle[PlayerViewModel.KEY_WAS_PLAYING] = snapshot.isPlaying
    }
}
