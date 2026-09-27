package dev.jellyboost.player.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Whether a cast status line ("Casting to <device>" / "Reconnecting to <device>…") is a polite live
 * region. Live while reconnecting, **and from then on**: a live region announces a change of its own
 * content, so one switched off in the same frame as the recovery never says "Casting to <device>" again,
 * and the listener is left on "Reconnecting…". Not live before the first reconnect: at session start
 * the transfer snackbar already says where the film went, and a second announcement would talk over it.
 */
fun castStatusIsLive(
    isReconnecting: Boolean,
    hasReconnected: Boolean,
): Boolean = isReconnecting || hasReconnected

/**
 * [castStatusIsLive] for one status line, remembering whether it has shown a reconnect. Scoped to the
 * line's own composition — the casting bar, the player's cast backdrop — which lives exactly as long as
 * the session it describes is on screen. Set after composition ([SideEffect]), so the frame that
 * recovers still finds the region live.
 */
@Composable
fun rememberCastStatusIsLive(isReconnecting: Boolean): Boolean {
    val hasReconnected = remember { mutableStateOf(false) }
    SideEffect { if (isReconnecting) hasReconnected.value = true }
    return castStatusIsLive(isReconnecting, hasReconnected.value)
}
