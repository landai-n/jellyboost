package dev.jellyboost.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.player.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player hides its controls after four seconds and reveals them on tap. Touch exploration
 * consumes taps, so a bare `pointerInput` gives a TalkBack user no way back — the fix is an
 * `onClick` semantics action, exactly the kind of modifier a gesture-layer refactor deletes
 * without noticing.
 */
@RunWith(AndroidJUnit4::class)
class PlayerGestureLayerA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theTapSurfaceIsAnActionAServiceCanInvokeAndNotOnlyAGesture() {
        var toggles = 0
        rule.setContent {
            PlayerGestureLayer(onToggleControls = { toggles++ }, onSeekBy = {})
        }

        val label = rule.activity.getString(R.string.player_show_controls)
        val surface = rule.onNodeWithContentDescription(label)
        assertNotNull(
            "the gesture layer must expose an accessibility click action",
            surface.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick),
        )

        // Invoked as a service would: the semantics action, not a synthesised touch.
        surface.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test
    fun theActionIsNamedForWhatItDoes() {
        rule.setContent {
            PlayerGestureLayer(onToggleControls = {}, onSeekBy = {})
        }

        val label = rule.activity.getString(R.string.player_show_controls)
        val node = rule.onNodeWithContentDescription(label).fetchSemanticsNode()
        // WCAG 2.5.3: the action's own label, not just the node's name — a service announcing
        // "double tap to activate" with no verb is what this replaced.
        assertEquals(label, node.config[SemanticsActions.OnClick].label)
        assertEquals(label, node.config[SemanticsProperties.ContentDescription].single())
    }
}
