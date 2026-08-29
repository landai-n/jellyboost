package dev.jellyboost.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The clearing on dispose is what every screen that is *not* Home depends on, and none of them can
 * see it: without it the frame keeps the last screen's artwork ground and paints a near-black band
 * and near-black circles over a light page. Deleting the `onDispose` leaves the unit suite green, so
 * it is pinned here.
 *
 * Instrumented rather than JVM because the guarantee is a composition-lifetime one and the unit
 * source sets carry no Compose runtime host (no Robolectric, and `ui-test-junit4` is wired to
 * `androidTestImplementation` only). This suite runs on the device as milestone DoD, not in
 * `/verify` — which is why the reporter also lives in `:core:ui` beside its test rather than in the
 * screen, so there is exactly one implementation for it to cover.
 */
@RunWith(AndroidJUnit4::class)
class ChromeBackdropTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theReporterHandsTheFrameWhatTheScreenReports() {
        val backdrop = ChromeBackdrop()
        var overMedia by mutableStateOf(false)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
                ReportChromeBackdrop { overMedia }
            }
        }

        composeTestRule.waitForIdle()
        assertFalse("nothing reported yet", backdrop.overMedia)

        overMedia = true
        composeTestRule.waitForIdle()
        assertTrue("the screen's artwork is under the chrome", backdrop.overMedia)
    }

    @Test
    fun theReporterClearsTheFrameWhenItLeavesTheComposition() {
        val backdrop = ChromeBackdrop()
        var present by mutableStateOf(true)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
                if (present) ReportChromeBackdrop { true }
            }
        }

        composeTestRule.waitForIdle()
        assertTrue("the reporter is composed and reporting", backdrop.overMedia)

        present = false
        composeTestRule.waitForIdle()
        assertFalse("the screen is gone, so its ground must be too", backdrop.overMedia)
    }
}
