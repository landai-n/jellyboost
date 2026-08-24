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
 * One real screen, swept by the Accessibility Test Framework.
 *
 * Everything else in the instrumented suite composes a component in isolation, which is what makes
 * those tests fast and precise — and also what makes them blind to the things that only exist once
 * a whole screen is on a real display: a touch target the layout squeezed, two nodes that ended up
 * speaking the same words. This launches the app as the launcher does and runs ATF over whatever it
 * lands on — the server-setup screen on a signed-out device, home on a signed-in one.
 *
 * It deliberately asserts nothing about *which* screen: the app is device-state-dependent, and a
 * test that demanded one of them would be a test about the tablet's session rather than about
 * accessibility.
 *
 * No Compose test rule here, on purpose. `MainActivity` holds the splash screen until session
 * restore answers, so the Compose hierarchy is not registered when the test body starts and
 * `ComposeTestRule` fails outright with "no compose hierarchies found". ATF works on the `View`
 * tree and the `AccessibilityNodeInfo` tree underneath it, neither of which needs that rule — so
 * the test waits for the content view to be laid out and sweeps it directly.
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

    /** Polls until the Compose host has actually been laid out, or the timeout runs out. */
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
     * ATF's one structural false positive on Compose — see `core/ui`'s `AccessibilityChecks` for
     * the full reasoning. `AndroidComposeView` is the single real `View` hosting the whole
     * hierarchy: focusable, textless, and reported as unlabelled in every sweep of any Compose
     * content. Everything a screen reader lands on inside it is a virtual node. Matched on the view
     * class *and* the check, so a real unlabelled control still fails this test.
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
