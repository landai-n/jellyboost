package dev.jellyboost.feature.settings

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeAccessibilityValidator
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator

typealias SettingsComposeRule =
    AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * A second copy of `:core:ui`'s `a11y/AccessibilityChecks`, deliberately: an `androidTest` source
 * set is not published, so no module can depend on another module's instrumented test code. Sharing
 * it would mean publishing a test-fixtures artifact from `:core:ui`. Behaviour is identical to that
 * copy, so a fix to either belongs in both.
 *
 * ATF walks the `AccessibilityNodeInfo` tree, which Compose generates from its semantics tree — the
 * layer a11y fixes live in, and the one Android Lint cannot see.
 */
class SettingsAccessibilityChecks(
    private val rule: SettingsComposeRule,
) {
    private val validator =
        AccessibilityValidator()
            .setRunChecksFromRootView(true)
            // Report rather than throw: ATF's own exception cannot distinguish a finding about one
            // of our rows from one about the View hosting all of them (see checkOrFail).
            .setThrowExceptionFor(null)

    fun install() {
        rule.setComposeAccessibilityValidator(
            object : ComposeAccessibilityValidator {
                override fun check(view: View) = checkOrFail(view)
            },
        )
    }

    /** Fails on any ATF *error* — the severity it reserves for findings that are certainly bugs. */
    fun assertClean() {
        rule.waitForIdle()
        rule.runOnUiThread {
            checkOrFail(rule.activity.findViewById(android.R.id.content))
        }
    }

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
     * `AndroidComposeView` is focusable with no text of its own, so `SpeakableTextPresentCheck`
     * reports it in *every* sweep of *any* Compose content. Suppressed on both axes at once — this
     * view class *and* this check — so a real row losing its label still fails.
     */
    private fun isComposeHostFalsePositive(result: AccessibilityViewCheckResult): Boolean =
        result.view?.javaClass?.name == COMPOSE_HOST_VIEW &&
            result.sourceCheckClass.simpleName == SPEAKABLE_TEXT_CHECK

    private companion object {
        const val COMPOSE_HOST_VIEW = "androidx.compose.ui.platform.AndroidComposeView"
        const val SPEAKABLE_TEXT_CHECK = "SpeakableTextPresentCheck"
    }
}
