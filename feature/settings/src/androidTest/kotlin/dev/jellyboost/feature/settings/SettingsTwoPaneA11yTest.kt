package dev.jellyboost.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The ≥840dp shape. The rail carries the **only** Back on the screen and the pane carries none;
 * the two together are still one screen, so a second Back beside the pane title would be an exit
 * from a place you cannot be.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTwoPaneA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val checks by lazy { SettingsAccessibilityChecks(rule) }

    @Before
    fun enableAccessibilityChecks() {
        checks.install()
    }

    @Test
    fun theTwoPaneShapePassesTheAccessibilityChecks() {
        rule.setContent { TwoPane() }

        checks.assertClean()
    }

    @Test
    fun theTwoPaneShapeStillPassesAtDoubleTheFontScale() {
        rule.setContent { TwoPane(fontScale = 2f) }

        checks.assertClean()
    }

    @Test
    fun theWholeScreenCarriesExactlyOneBackAndNoHome() {
        rule.setContent { TwoPane() }

        rule
            .onAllNodesWithContentDescription(rule.activity.getString(CoreUiR.string.action_back))
            .assertCountEquals(1)
        rule
            .onAllNodesWithContentDescription(rule.activity.getString(CoreUiR.string.action_home))
            .assertCountEquals(0)
    }

    @Test
    fun thePaneOpensOnPlaybackAndTheRailSwapsItWithoutLeavingTheScreen() {
        rule.setContent { TwoPane() }

        rule.onNodeWithText(rule.activity.getString(R.string.settings_skip_intro)).assertExists()

        rule
            .onNodeWithText(rule.activity.getString(R.string.settings_section_downloads), substring = true)
            .performClick()

        rule.onNodeWithText(rule.activity.getString(R.string.settings_storage_picker)).assertExists()
        rule
            .onNodeWithText(rule.activity.getString(R.string.settings_section_appearance), substring = true)
            .assertExists()
    }

    @Test
    fun theIdentityRowOpensTheAccountPaneRatherThanPushingAScreen() {
        rule.setContent { TwoPane() }

        rule.onNodeWithText(ACCOUNT_NAME, substring = true).performClick()

        rule.onNodeWithText(rule.activity.getString(R.string.settings_sign_out)).assertExists()
        rule
            .onAllNodesWithContentDescription(rule.activity.getString(CoreUiR.string.action_back))
            .assertCountEquals(1)
    }

    @Composable
    private fun TwoPane(fontScale: Float = 1f) {
        TestViewport(width = WIDE_WIDTH, height = WIDE_HEIGHT, fontScale = fontScale) {
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

    private companion object {
        /** The test tablet in landscape — the orientation that crosses the 840dp cutoff. */
        val WIDE_WIDTH = 1138.dp
        val WIDE_HEIGHT = 711.dp
        const val ACCOUNT_NAME = "casey"
    }
}
