package dev.jellyboost.core.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.a11y.AccessibilityChecks
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swapping a screen's content destroys the node a screen-reader user was standing on, so the
 * replacement must announce itself. The easily-broken part is the Retry button staying its own node
 * rather than being swallowed into the sentence.
 */
@RunWith(AndroidJUnit4::class)
class StateViewsA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val checks by lazy { AccessibilityChecks(rule) }

    @Before
    fun enableAccessibilityChecks() {
        checks.install()
    }

    @Test
    fun theLoadingSpinnerIsAPoliteLiveRegionWithAName() {
        rule.setContent { JellyfinTheme { LoadingState() } }

        val loading = rule.activity.getString(R.string.state_loading)
        val node = rule.onNodeWithContentDescription(loading).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        checks.assertClean()
    }

    @Test
    fun anErrorStateAnnouncesAssertivelyWhenAskedTo() {
        rule.setContent {
            JellyfinTheme {
                ErrorState(message = MESSAGE, onRetry = {}, announce = LiveRegionMode.Assertive)
            }
        }

        val node = rule.onNodeWithText(MESSAGE).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Assertive, node.config[SemanticsProperties.LiveRegion])
    }

    @Test
    fun aStateViewStaysSilentByDefault() {
        // Search draws its own announcement; announcing unconditionally would say it twice.
        rule.setContent { JellyfinTheme { EmptyState(message = MESSAGE) } }

        val node = rule.onNodeWithText(MESSAGE).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun theRetryButtonIsItsOwnNodeOutsideTheAnnouncement() {
        rule.setContent {
            JellyfinTheme {
                ErrorState(message = MESSAGE, onRetry = {}, announce = LiveRegionMode.Assertive)
            }
        }

        val retry = rule.activity.getString(R.string.state_retry)
        rule.onNodeWithText(retry).assertIsDisplayed()
        rule.onNode(hasClickAction()).assertIsDisplayed()
        // If the panel took the live region instead, this text node would swallow the button.
        val message = rule.onNodeWithText(MESSAGE).fetchSemanticsNode().config
        assertNull(message.getOrNull(SemanticsActions.OnClick))
        checks.assertClean()
    }

    private companion object {
        const val MESSAGE = "Could not reach the server."
    }
}
