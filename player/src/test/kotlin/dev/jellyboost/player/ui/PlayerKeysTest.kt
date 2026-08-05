package dev.jellyboost.player.ui

import androidx.compose.ui.input.key.Key
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the player's keyboard table ([playerKeyBinding]).
 *
 * The mapping is worth pinning without a device for two reasons: which key does what is a promise to
 * a keyboard user, and *which pass handles it* is the difference between shortcuts that help and
 * shortcuts that steal — an arrow consumed at the root is an arrow the focused seek bar never sees.
 */
class PlayerKeysTest {
    @Test
    fun `space toggles play, but only if nothing focused wanted it`() {
        playerKeyBinding(Key.Spacebar) shouldBe
            PlayerKeyBinding(PlayerKeyCommand.PlayPause, PlayerKeyScope.Unhandled)
    }

    @Test
    fun `the media play-pause key wins wherever focus is`() {
        playerKeyBinding(Key.MediaPlayPause) shouldBe
            PlayerKeyBinding(PlayerKeyCommand.PlayPause, PlayerKeyScope.Always)
    }

    @Test
    fun `the arrows seek by the transport's own amounts, and only while the root has focus`() {
        playerKeyBinding(Key.DirectionLeft) shouldBe
            PlayerKeyBinding(PlayerKeyCommand.SeekBack, PlayerKeyScope.RootFocused)
        playerKeyBinding(Key.DirectionRight) shouldBe
            PlayerKeyBinding(PlayerKeyCommand.SeekForward, PlayerKeyScope.RootFocused)
    }

    @Test
    fun `escape closes the player`() {
        playerKeyBinding(Key.Escape) shouldBe
            PlayerKeyBinding(PlayerKeyCommand.Back, PlayerKeyScope.Unhandled)
    }

    @Test
    fun `the keys focus traversal needs are left alone`() {
        // Consuming any of these would trap a keyboard user on whatever they had tabbed to.
        playerKeyBinding(Key.Tab).shouldBeNull()
        playerKeyBinding(Key.Enter).shouldBeNull()
        playerKeyBinding(Key.DirectionCenter).shouldBeNull()
        playerKeyBinding(Key.DirectionUp).shouldBeNull()
        playerKeyBinding(Key.DirectionDown).shouldBeNull()
    }
}
