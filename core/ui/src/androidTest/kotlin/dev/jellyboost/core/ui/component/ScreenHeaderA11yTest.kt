package dev.jellyboost.core.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.a11y.AccessibilityChecks
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenHeaderA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val checks by lazy { AccessibilityChecks(rule) }

    @Before
    fun enableAccessibilityChecks() {
        checks.install()
    }

    @Test
    fun omittingOnHomeDrawsBackAsTheOnlyLeadingControl() {
        rule.setContent {
            JellyfinTheme {
                ScreenHeader(onBack = {}) { ScreenHeaderTitle(text = TITLE) }
            }
        }

        rule.onAllNodesWithContentDescription(back()).assertCountEquals(1)
        rule.onAllNodesWithContentDescription(home()).assertCountEquals(0)
        checks.assertClean()
    }

    @Test
    fun passingOnHomeDrawsHomeBesideBack() {
        rule.setContent {
            JellyfinTheme {
                ScreenHeader(onBack = {}, onHome = {}) { ScreenHeaderTitle(text = TITLE) }
            }
        }

        rule.onAllNodesWithContentDescription(back()).assertCountEquals(1)
        rule.onAllNodesWithContentDescription(home()).assertCountEquals(1)
        checks.assertClean()
    }

    @Test
    fun eachButtonInvokesItsOwnHandler() {
        var backs = 0
        var homes = 0
        rule.setContent {
            JellyfinTheme {
                ScreenHeader(onBack = { backs++ }, onHome = { homes++ }) {
                    ScreenHeaderTitle(text = TITLE)
                }
            }
        }

        rule.onAllNodesWithContentDescription(back())[0].performClick()
        rule.onAllNodesWithContentDescription(home())[0].performClick()

        assertEquals(1, backs)
        assertEquals(1, homes)
    }

    private fun back() = rule.activity.getString(R.string.action_back)

    private fun home() = rule.activity.getString(R.string.action_home)

    private companion object {
        const val TITLE = "Settings"
    }
}
