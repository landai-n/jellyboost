package dev.jellyboost.app

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real screen, swept by the Accessibility Test Framework — whichever screen the app lands on.
 * Asserting *which* would make it a test about the device's session.
 *
 * No Compose test rule, on purpose: `MainActivity` holds the splash screen until session restore
 * answers, so the Compose hierarchy is not registered when the test body starts and `ComposeTestRule`
 * fails with "no compose hierarchies found". ATF needs only the `View` tree.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchA11ySmokeTest {
    @get:Rule
    val scenarioRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun theScreenTheAppOpensOnPassesTheAccessibilityChecks() {
        assertTrue("the app never drew a screen within ${LAUNCH_TIMEOUT_MS}ms", awaitComposedScreen())

        scenarioRule.scenario.onActivity { activity ->
            val failures =
                AccessibilityValidator()
                    .setRunChecksFromRootView(true)
                    .setThrowExceptionFor(null)
                    .checkAndReturnResults(activity.findViewById(android.R.id.content))
                    .filter { it.type == AccessibilityCheckResultType.ERROR }
                    .filterNot(::isComposeHostFalsePositive)
            if (failures.isNotEmpty()) {
                error(
                    failures.joinToString(separator = "\n") { result ->
                        "${result.view?.javaClass?.name} :: ${result.type} :: ${result.message}"
                    },
                )
            }
        }
    }

    private fun awaitComposedScreen(): Boolean {
        val deadline = SystemClock.uptimeMillis() + LAUNCH_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var drawn = false
            scenarioRule.scenario.onActivity { activity ->
                drawn = activity.findViewById<View>(android.R.id.content).hasDrawnComposeContent()
            }
            if (drawn) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun View.hasDrawnComposeContent(): Boolean {
        if (javaClass.name == COMPOSE_HOST_VIEW) return width > 0 && height > 0
        val group = this as? ViewGroup ?: return false
        return (0 until group.childCount).any { group.getChildAt(it).hasDrawnComposeContent() }
    }

    /**
     * ATF's one structural false positive on Compose: `AndroidComposeView` is focusable, textless and
     * reported unlabelled in every sweep, while everything inside it is a virtual node. Matched on the
     * view class *and* the check, so a real unlabelled control still fails.
     */
    private fun isComposeHostFalsePositive(result: AccessibilityViewCheckResult): Boolean =
        result.view?.javaClass?.name == COMPOSE_HOST_VIEW &&
            result.sourceCheckClass.simpleName == SPEAKABLE_TEXT_CHECK

    private companion object {
        const val COMPOSE_HOST_VIEW = "androidx.compose.ui.platform.AndroidComposeView"
        const val SPEAKABLE_TEXT_CHECK = "SpeakableTextPresentCheck"

        /** Generous: the first launch after an install pays for session restore and a cold graph. */
        const val LAUNCH_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
