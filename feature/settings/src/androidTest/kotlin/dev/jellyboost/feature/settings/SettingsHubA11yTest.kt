package dev.jellyboost.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The hub is a list of one-node buttons. Every row must speak its title *and* its state summary in
 * one stop — a summary a reader has to swipe to is not a summary, it is a second row — and the
 * chevron and the category glyph must never be stops of their own.
 */
@RunWith(AndroidJUnit4::class)
class SettingsHubA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val checks by lazy { SettingsAccessibilityChecks(rule) }

    @Before
    fun enableAccessibilityChecks() {
        checks.install()
    }

    @Test
    fun theHubPassesTheAccessibilityChecks() {
        setHub()

        checks.assertClean()
    }

    @Test
    fun theHubStillPassesAtDoubleTheFontScale() {
        setHub(fontScale = 2f)

        checks.assertClean()
    }

    @Test
    fun everyCategoryRowIsOneButtonThatSpeaksItsSummaryToo() {
        setHub()

        SettingsCategory.entries.forEach { category ->
            val title = rule.activity.getString(category.titleRes)
            val node = rule.onNodeWithText(title, substring = true).fetchSemanticsNode()

            assertEquals(
                "$title is not a button",
                Role.Button,
                node.config.getOrNull(SemanticsProperties.Role),
            )
            val spoken =
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString(" ")
            assertTrue(
                "$title speaks only '$spoken' — the state summary is not in the same node",
                spoken.length > title.length,
            )
        }
    }

    @Test
    fun everyRowClearsTheTouchTargetMinimum() {
        setHub()

        SettingsCategory.entries.forEach { category ->
            rule
                .onNodeWithText(rule.activity.getString(category.titleRes), substring = true)
                .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
        }
    }

    @Test
    fun theIdentityRowIsOneButtonNamingTheUserAndTheServer() {
        setHub()

        val node =
            rule.onNodeWithText(ACCOUNT_NAME, substring = true).fetchSemanticsNode()
        val spoken =
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString(" ")

        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertTrue("the identity row does not name the server: '$spoken'", spoken.contains(SERVER))
    }

    @Test
    fun theHubCarriesNoHomeButton() {
        setHub()

        rule
            .onAllNodesWithContentDescription(
                rule.activity.getString(CoreUiR.string.action_home),
            ).assertCountEquals(0)
    }

    @Test
    fun theHubCarriesExactlyOneBackButton() {
        setHub()

        rule
            .onAllNodesWithContentDescription(
                rule.activity.getString(CoreUiR.string.action_back),
            ).assertCountEquals(1)
    }

    private fun setHub(fontScale: Float = 1f) {
        rule.setContent {
            TestViewport(width = COMPACT_WIDTH, fontScale = fontScale) {
                SettingsContent(
                    state = TEST_STATE,
                    actions = NO_OP_ACTIONS,
                    onBack = {},
                    onOpenCategory = {},
                    onOpenAccount = {},
                    onOpenLicence = {},
                    onOpenThirdPartyLicences = {},
                    appVersion = TEST_APP_VERSION,
                )
            }
        }
    }

    private companion object {
        val COMPACT_WIDTH = 400.dp
        val MIN_TOUCH_TARGET = 48.dp
        const val ACCOUNT_NAME = "casey"
        const val SERVER = "test-server"
    }
}
