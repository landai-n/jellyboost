package dev.jellyboost.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.common.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * Every category page, swept by ATF at the default scale and at 2.0, plus the row invariants that
 * are invisible on screen: the whole row is the target and the control inside it is inert, a choice
 * row repeats its group in its own description, and the info row is one node.
 */
@RunWith(AndroidJUnit4::class)
class SettingsCategoryPagesA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val checks by lazy { SettingsAccessibilityChecks(rule) }

    @Before
    fun enableAccessibilityChecks() {
        checks.install()
    }

    @Test
    fun everyCategoryPagePassesTheAccessibilityChecks() {
        SettingsPane.entries.forEach { pane ->
            rule.setContent { Page(pane) }
            checks.assertClean()
        }
    }

    @Test
    fun everyCategoryPageStillPassesAtDoubleTheFontScale() {
        SettingsPane.entries.forEach { pane ->
            rule.setContent { Page(pane, fontScale = 2f) }
            checks.assertClean()
        }
    }

    @Test
    fun aSwitchRowIsOneToggleableTargetAndTheSwitchInsideItIsInert() {
        rule.setContent { Page(SettingsPane.PLAYBACK) }

        val label = rule.activity.getString(R.string.settings_pip)
        val node = rule.onNodeWithText(label, substring = true).fetchSemanticsNode()

        assertEquals(Role.Switch, node.config.getOrNull(SemanticsProperties.Role))
        assertNotNull(
            "the row does not carry the toggle state",
            node.config.getOrNull(SemanticsProperties.ToggleableState),
        )
        rule.onNodeWithText(label, substring = true).assertHeightIsAtLeast(MIN_TOUCH_TARGET)
    }

    @Test
    fun bothSkipGroupsRepeatTheirOwnNameSoTheirIdenticalOptionsCanBeToldApart() {
        rule.setContent { Page(SettingsPane.PLAYBACK) }

        val option = rule.activity.getString(R.string.settings_skip_mode_auto)
        listOf(R.string.settings_skip_intro, R.string.settings_skip_outro).forEach { groupRes ->
            val group = rule.activity.getString(groupRes)
            rule
                .onNodeWithContentDescription(
                    choiceRowDescription(group, option, supportingText = null, actionHint = null),
                ).assertExists()
        }
    }

    @Test
    fun aChoiceRowIsSelectableAtTheRowLevelAndItsRadioIsInert() {
        rule.setContent { Page(SettingsPane.APPEARANCE) }

        val group = rule.activity.getString(R.string.settings_theme)
        val option = rule.activity.getString(R.string.settings_theme_system)
        val row =
            rule.onNodeWithContentDescription(
                choiceRowDescription(group, option, supportingText = null, actionHint = null),
            )

        val node = row.fetchSemanticsNode()
        assertEquals(Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals(true, node.config.getOrNull(SemanticsProperties.Selected))
        row.assertHeightIsAtLeast(MIN_TOUCH_TARGET)
    }

    @Test
    fun theStorageInfoRowIsOneStopNotACaptionAndAValue() {
        rule.setContent { Page(SettingsPane.DOWNLOADS) }

        val label = rule.activity.getString(R.string.settings_storage_label)
        val node = rule.onNodeWithText(label, substring = true).fetchSemanticsNode()
        val spoken =
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString(" ")

        assertTrue(
            "the storage row speaks only '$spoken' — the figure is a separate stop",
            spoken.length > label.length,
        )
        assertEquals(
            "a read-only row must not be clickable",
            0,
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().count { it.id == node.id },
        )
    }

    @Test
    fun theStorageLocationPickerRepeatsItsGroupOnEveryVolume() {
        rule.setContent { Page(SettingsPane.DOWNLOADS) }

        val group = rule.activity.getString(R.string.settings_storage_picker)
        TWO_VOLUMES.volumes.forEach { volume ->
            val free =
                rule.activity.getString(
                    R.string.settings_storage_volume_free,
                    formatBytes(volume.availableBytes),
                )
            rule
                .onNodeWithContentDescription(
                    choiceRowDescription(
                        groupLabel = group,
                        label = volume.description.orEmpty(),
                        supportingText = free,
                        actionHint = null,
                    ),
                ).assertExists()
        }
    }

    @Test
    fun everyCategoryPageHeaderCarriesBackAndNoHome() {
        SettingsPane.entries.forEach { pane ->
            rule.setContent { PageWithHeader(pane) }

            rule
                .onAllNodesWithContentDescription(rule.activity.getString(CoreUiR.string.action_back))
                .assertCountEquals(1)
            rule
                .onAllNodesWithContentDescription(rule.activity.getString(CoreUiR.string.action_home))
                .assertCountEquals(0)
        }
    }

    @Composable
    private fun Page(
        pane: SettingsPane,
        fontScale: Float = 1f,
    ) {
        TestViewport(width = COMPACT_WIDTH, fontScale = fontScale) {
            SettingsCategoryBody(
                pane = pane,
                state = TEST_STATE,
                actions = NO_OP_ACTIONS,
                appVersion = TEST_APP_VERSION,
                onOpenLicence = {},
                onOpenThirdPartyLicences = {},
            )
        }
    }

    @Composable
    private fun PageWithHeader(pane: SettingsPane) {
        TestViewport(width = COMPACT_WIDTH) {
            SettingsPageChrome(pane = pane, onBack = {}) {
                SettingsCategoryBody(
                    pane = pane,
                    state = TEST_STATE,
                    actions = NO_OP_ACTIONS,
                    appVersion = TEST_APP_VERSION,
                    onOpenLicence = {},
                    onOpenThirdPartyLicences = {},
                )
            }
        }
    }

    private companion object {
        val COMPACT_WIDTH = 400.dp
        val MIN_TOUCH_TARGET = 48.dp
    }
}
