package dev.jellyboost.player.ui

import androidx.lifecycle.SavedStateHandle
import dev.jellyboost.player.model.PlaybackSnapshot

/**
 * The handle holds two kinds of value under one type: the route's `ARG_` arguments, read-only and to be left
 * intact, and the `KEY_` session position, rewritten every five seconds.
 */
internal class PlayerSessionStore(
    private val handle: SavedStateHandle,
) {
    val itemId: String =
        requireNotNull(handle.get<String>(PlayerViewModel.ARG_ITEM_ID)) {
            "Player route is missing its '${PlayerViewModel.ARG_ITEM_ID}' argument"
        }

    val mediaSourceId: String? = handle[PlayerViewModel.ARG_MEDIA_SOURCE_ID]

    /** Non-null only after a process death, from what the last progress tick wrote ([rememberLivePosition]). */
    private val restoredPositionTicks: Long? = handle[PlayerViewModel.KEY_LIVE_POSITION_TICKS]

    val startPositionTicks: Long =
        restoredPositionTicks ?: handle[PlayerViewModel.ARG_START_TICKS] ?: 0L

    /** A restore resumes only what was actually running; a paused item must not start playing to an empty room. */
    val playWhenReady: Boolean =
        restoredPositionTicks == null || handle.get<Boolean>(PlayerViewModel.KEY_WAS_PLAYING) == true

    /**
     * Without this a restored back stack re-opens at the position Play was *tapped* at, and the next progress
     * tick stamps that stale position with a fresh timestamp for most-recent-wins sync to propagate.
     *
     * Position 0 is not written: it cannot be told from "no session yet".
     */
    fun rememberLivePosition(snapshot: PlaybackSnapshot) {
        if (snapshot.positionTicks <= 0L) return
        handle[PlayerViewModel.KEY_LIVE_POSITION_TICKS] = snapshot.positionTicks
        handle[PlayerViewModel.KEY_WAS_PLAYING] = snapshot.isPlaying
    }
}
