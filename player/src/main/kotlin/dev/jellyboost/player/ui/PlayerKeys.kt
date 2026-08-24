package dev.jellyboost.player.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

internal enum class PlayerKeyCommand {
    PlayPause,
    SeekBack,
    SeekForward,
    Back,
}

/**
 * Each key belongs in the pass where it cannot steal from a focused control: handling everything in the preview
 * pass would make Space seek instead of pressing the focused button.
 */
internal enum class PlayerKeyScope {
    /** Preview pass: nothing focusable claims media keys. */
    Always,

    /** Bubble pass: the player only sees what no focused control consumed. */
    Unhandled,

    /** Bubble pass and root focus: Compose traverses focus with the arrows *after* bubbling, so consuming them
     * unconditionally would strand a keyboard user on the control they tabbed to. */
    RootFocused,
}

internal data class PlayerKeyBinding(
    val command: PlayerKeyCommand,
    val scope: PlayerKeyScope,
)

/** Tab, the d-pad centre and Enter deliberately stay unmapped: they must keep reaching the focus system. */
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

/** @param onShowControls run before every command, whatever the command is. */
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
 * Key *down* only: acting on the release as well would seek twice.
 *
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
