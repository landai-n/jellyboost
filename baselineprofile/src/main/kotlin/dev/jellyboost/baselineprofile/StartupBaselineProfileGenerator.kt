package dev.jellyboost.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Records the baseline profile for cold start and for the first scrollable surface the app shows.
 *
 * Run on a device (rooted emulator, or a `userdebug`/AOSP image — a stock retail device cannot read
 * the profile back):
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * The result is written to `app/src/main/generated/baselineProfiles/` and is checked in, so CI
 * and any machine without a device still package the profile.
 *
 * Scope note: the flows below stop at whatever the app shows without credentials — cold start,
 * Hilt graph construction, Room open, the Compose/Material theme, navigation, and the first frame.
 * That is where the interpretation cost of a cold start actually is. Extending the walk through
 * login into Home and the library grid needs a session on the generating device; when that device
 * session happens, add a second `@Test` here rather than growing this one — `mergeIntoMain` folds
 * both into the same profile.
 */
@RunWith(JUnit4::class)
class StartupBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(packageName = APP_PACKAGE) {
            pressHome()
            startActivityAndWait()

            // Give the first composition a moment to settle; the profile wants the classes loaded
            // during layout and draw, not just those reached by the time the window appears.
            device.waitForIdle()

            // Whatever the first screen is, exercise its scroll machinery if it has any. Absent a
            // session that is the server-setup list; with one it is Home. `findObject` returns null
            // rather than throwing, so this stays valid in both states.
            val scrollable = device.wait(Until.findObject(By.scrollable(true)), SCROLLABLE_TIMEOUT_MS)
            if (scrollable != null) {
                scrollable.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
                repeat(SCROLL_PASSES) {
                    scrollable.scroll(Direction.DOWN, SCROLL_PERCENT)
                    device.waitForIdle()
                }
                scrollable.scroll(Direction.UP, 1f)
                device.waitForIdle()
            }
        }
    }

    private companion object {
        /**
         * The release variant's application id. Debug appends `.debug`; the profile is generated
         * against the release-shaped `nonMinifiedRelease` variant, which does not.
         */
        const val APP_PACKAGE = "dev.jellyboost.app"
        const val SCROLLABLE_TIMEOUT_MS = 5_000L
        const val GESTURE_MARGIN_FRACTION = 5
        const val SCROLL_PASSES = 3
        const val SCROLL_PERCENT = 0.8f
    }
}
