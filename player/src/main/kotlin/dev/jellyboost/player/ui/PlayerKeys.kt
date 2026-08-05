package dev.jellyboost.player.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The player's keyboard layer (accessibility audit 2026-08-05, CR-4).
 *
 * The app is tablet-first and had no keyboard support at all: with a case keyboard, an external
 * mouse-and-keyboard setup or a switch device that emits key codes, the only reachable transport was
 * the media keys the `MediaSession` already answers. The mapping lives here, apart from
 * `PlayerScreen`, because *which key does what* is a table worth testing without a device.
 */
internal enum class PlayerKeyCommand {
    PlayPause,
    SeekBack,
    SeekForward,
    Back,
}

/**
 * When a key's command may run, which is the whole subtlety of putting shortcuts on a screen that
 * also has focusable controls.
 *
 * Compose dispatches a key event down the tree (preview) and then back up from whatever holds focus
 * (bubble). Handling everything in the preview pass would be simplest and wrong: Space would fire
 * play/pause instead of pressing the focused button, and an arrow would seek instead of nudging the
 * focused seek bar. So each key is placed in the pass where it cannot take something away.
 */
internal enum class PlayerKeyScope {
    /** Media keys. No focusable control claims them, so they win wherever focus happens to be. */
    Always,

    /**
     * Keys a focused control may want first — Space presses a button, Escape closes a sheet. Handled
     * on the way back up, so the player only sees what nothing else consumed.
     */
    Unhandled,

    /**
     * Arrows, which additionally require the player root itself to hold focus.
     *
     * Compose moves focus with the arrow keys *after* the bubble pass, so consuming them
     * unconditionally would strand a keyboard user on whichever control they tabbed to. While the
     * root has focus — which is where the player starts, and where it stays until something is
     * tabbed to — there is nothing to traverse and they seek instead.
     */
    RootFocused,
}

internal data class PlayerKeyBinding(
    val command: PlayerKeyCommand,
    val scope: PlayerKeyScope,
)

/**
 * What a hardware key does in the player, or `null` for the keys it leaves alone — which is most of
 * them, deliberately: Tab, the d-pad centre and Enter must keep reaching the focus system, or the
 * shortcuts would have cost more than they gave.
 */
internal fun playerKeyBinding(key: Key): PlayerKeyBinding? =
    when (key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause ->
            PlayerKeyBinding(PlayerKeyCommand.PlayPause, PlayerKeyScope.Always)

        Key.MediaFastForward -> PlayerKeyBinding(PlayerKeyCommand.SeekForward, PlayerKeyScope.Always)
        Key.MediaRewind -> PlayerKeyBinding(PlayerKeyCommand.SeekBack, PlayerKeyScope.Always)

        Key.Spacebar -> PlayerKeyBinding(PlayerKeyCommand.PlayPause, PlayerKeyScope.Unhandled)
        Key.Escape -> PlayerKeyBinding(PlayerKeyCommand.Back, PlayerKeyScope.Unhandled)

        Key.DirectionLeft -> PlayerKeyBinding(PlayerKeyCommand.SeekBack, PlayerKeyScope.RootFocused)
        Key.DirectionRight -> PlayerKeyBinding(PlayerKeyCommand.SeekForward, PlayerKeyScope.RootFocused)

        else -> null
    }

/**
 * Binds the key table to the things the player can actually do.
 *
 * Built outside the screen composable so that the table's `when` is not one more branch in a
 * function that is already a screen, and so that the one thing every shortcut shares — bringing the
 * controls back, the keyboard's counterpart of CR-1's tap — is stated once.
 *
 * @param onShowControls run before every command, whatever the command is.
 */
internal fun playerKeyRunner(
    actions: PlayerActions,
    onShowControls: () -> Unit,
): (PlayerKeyCommand) -> Boolean =
    { command ->
        onShowControls()
        when (command) {
            PlayerKeyCommand.PlayPause -> actions.onPlayPause()
            PlayerKeyCommand.SeekBack -> actions.onSeekBy(-SKIP_BACK_MS)
            PlayerKeyCommand.SeekForward -> actions.onSeekBy(SKIP_FORWARD_MS)
            PlayerKeyCommand.Back -> actions.onBack()
        }
        true
    }

/**
 * Dispatches one key event, in one of the two passes.
 *
 * Key *down* only: a key that acted on its release as well would seek twice, and returning `false`
 * for the up event is what keeps the rest of the system's key handling intact.
 *
 * @param rootFocused whether the player root — rather than one of the controls on it — holds focus.
 * @param preview `true` for the downward pass, `false` for the bubble.
 * @return `true` only when the event was consumed, so everything unmapped still traverses.
 */
internal fun handlePlayerKey(
    event: KeyEvent,
    rootFocused: Boolean,
    preview: Boolean,
    run: (PlayerKeyCommand) -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val binding = playerKeyBinding(event.key) ?: return false
    val handledHere =
        when (binding.scope) {
            PlayerKeyScope.Always -> preview
            PlayerKeyScope.Unhandled -> !preview
            PlayerKeyScope.RootFocused -> !preview && rootFocused
        }
    return handledHere && run(binding.command)
}
