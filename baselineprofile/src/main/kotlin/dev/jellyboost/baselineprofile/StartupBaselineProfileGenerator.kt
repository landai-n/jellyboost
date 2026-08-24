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
 * `./gradlew :app:generateBaselineProfile`, on a rooted emulator or a `userdebug`/AOSP image — a
 * stock retail device cannot read the profile back. The result is checked in under
 * `app/src/main/generated/baselineProfiles/`, so machines without a device still package it.
 *
 * The walk stops at whatever the app shows without credentials. Extending it through login needs a
 * session on the generating device; add a second `@Test` rather than growing this one, since
 * `mergeIntoMain` folds both into the same profile.
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

            // The profile wants the classes loaded during layout and draw, not only those reached
            // by the time the window appears.
            device.waitForIdle()

            // The first screen differs with the device's session; `findObject` returns null rather
            // than throwing, so this stays valid in both states.
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
        /** Debug appends `.debug`; the profile is generated against `nonMinifiedRelease`, which does not. */
        const val APP_PACKAGE = "dev.jellyboost.app"
        const val SCROLLABLE_TIMEOUT_MS = 5_000L
        const val GESTURE_MARGIN_FRACTION = 5
        const val SCROLL_PASSES = 3
        const val SCROLL_PERCENT = 0.8f
    }
}
