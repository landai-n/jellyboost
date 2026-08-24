package dev.jellyboost.core.ui.a11y

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeAccessibilityValidator
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator

/** What `createAndroidComposeRule<ComponentActivity>()` actually returns, spelled once. */
typealias ComposeRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * Google's Accessibility Test Framework, pointed at what Compose publishes to the platform.
 *
 * ATF is a *View* checker: it walks the `AccessibilityNodeInfo` tree an accessibility service would
 * see. In a Compose app that tree is generated from the semantics tree, which is exactly the layer
 * accessibility fixes live in — so running ATF over a composed component checks the real
 * article (unlabelled clickables, duplicate speakable text, touch targets under 48dp, text
 * contrast) rather than a proxy for it. Android Lint cannot see any of this; that is the division of
 * labour recorded in `config/lint/lint.xml`.
 *
 * Two hooks, deliberately:
 * - [install] registers the validator with the Compose test rule, so every action the test performs
 *   is checked as it happens;
 * - [assertClean] runs it over the whole composed tree on demand, which is what a test that only
 *   *asserts* needs — an assertion performs no action and would otherwise check nothing.
 */
class AccessibilityChecks(
    private val rule: ComposeRule,
) {
    private val validator =
        AccessibilityValidator()
            .setRunChecksFromRootView(true)
            // Report rather than throw: this class decides what is a failure, in [checkOrFail],
            // because ATF's own exception cannot distinguish a finding about one of our components
            // from a finding about the View that hosts all of them.
            .setThrowExceptionFor(null)

    /** Checks every action this test performs, from here on. */
    fun install() {
        rule.setComposeAccessibilityValidator(
            object : ComposeAccessibilityValidator {
                override fun check(view: View) = checkOrFail(view)
            },
        )
    }

    /**
     * Runs the checks over everything currently composed.
     *
     * Throws `AccessibilityViewCheckException` on any ATF *error* — the validator's default, and
     * the severity ATF reserves for the findings that are certainly bugs.
     */
    fun assertClean() {
        rule.waitForIdle()
        rule.runOnUiThread {
            checkOrFail(rule.activity.findViewById(android.R.id.content))
        }
    }

    /**
     * Runs ATF and fails with the offending view's class name rather than ATF's own message.
     *
     * ATF describes a Compose finding as "View with no valid resource name", which is true of every
     * node in a Compose app and therefore useless for working out what to fix. The class name is
     * what tells you whether you are looking at a real component or at the host view.
     */
    private fun checkOrFail(view: View) {
        val failures =
            validator
                .checkAndReturnResults(view)
                .filter { it.type == AccessibilityCheckResultType.ERROR }
                .filterNot(::isComposeHostFalsePositive)
        if (failures.isEmpty()) return
        error(
            failures.joinToString(separator = "\n") { result ->
                "${result.view?.javaClass?.name} :: ${result.type} :: ${result.message}"
            },
        )
    }

    /**
     * The one finding that is about Compose rather than about this app.
     *
     * `AndroidComposeView` is the single real `View` that hosts an entire Compose hierarchy. It is
     * focusable and it has no text of its own, which is precisely what `SpeakableTextPresentCheck`
     * looks for — so ATF reports it as an unlabelled item in *every* sweep of *any* Compose content,
     * including a screen consisting of one labelled spinner. Everything a screen reader actually
     * reads inside it is a **virtual** accessibility node published from the semantics tree, and
     * those are what the rest of ATF's checks then walk; the host is a container that no service
     * ever lands on.
     *
     * Suppressed on both axes at once — this exact view class *and* this exact check — so a real
     * component that loses its label still fails, and so does anything else ATF finds on the host.
     */
    private fun isComposeHostFalsePositive(result: AccessibilityViewCheckResult): Boolean =
        result.view?.javaClass?.name == COMPOSE_HOST_VIEW &&
            result.sourceCheckClass.simpleName == SPEAKABLE_TEXT_CHECK

    private companion object {
        const val COMPOSE_HOST_VIEW = "androidx.compose.ui.platform.AndroidComposeView"
        const val SPEAKABLE_TEXT_CHECK = "SpeakableTextPresentCheck"
    }
}
