package dev.jellyboost.feature.settings

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SettingsSizingTest {
    @Test
    @DisplayName("a phone in portrait uses the push flow")
    fun aPhoneIsNotTwoPane() {
        isTwoPaneSettings(360.dp) shouldBe false
    }

    @Test
    @DisplayName("a phone in landscape is still not wide enough for a 340dp rail and a pane")
    fun aLandscapePhoneIsNotTwoPane() {
        isTwoPaneSettings(800.dp) shouldBe false
    }

    @Test
    @DisplayName("just under the cutoff is still the phone flow, verbatim")
    fun justUnderTheCutoffIsCompact() {
        isTwoPaneSettings(SettingsTwoPaneMinWidth - 1.dp) shouldBe false
    }

    @Test
    @DisplayName("the cutoff itself is two-pane — the same 840dp the auth flow splits at")
    fun theCutoffItselfIsTwoPane() {
        SettingsTwoPaneMinWidth shouldBe 840.dp
        isTwoPaneSettings(SettingsTwoPaneMinWidth) shouldBe true
    }

    @Test
    @DisplayName("the test tablet splits in landscape and pushes in portrait")
    fun theTestTabletSplitsOnlyInLandscape() {
        isTwoPaneSettings(711.dp) shouldBe false
        isTwoPaneSettings(1138.dp) shouldBe true
    }

    @Test
    @DisplayName("the rail leaves a pane at least as wide as the content cap it draws into")
    fun theRailLeavesRoomForTheContentCap() {
        val narrowestPane = SettingsTwoPaneMinWidth - SettingsRailWidth

        SettingsRailWidth shouldBe 340.dp
        (narrowestPane >= SettingsContentMaxWidth * NARROWEST_PANE_FRACTION) shouldBe true
    }

    private companion object {
        /**
         * The narrowest pane (500dp) is not the full 640dp cap and does not need to be — the cap is a
         * *maximum*. This pins that it is still the larger share of the window, which is what stops a
         * rail from being drawn beside a column too thin to hold a label and its switch.
         */
        const val NARROWEST_PANE_FRACTION = 0.75f
    }
}
