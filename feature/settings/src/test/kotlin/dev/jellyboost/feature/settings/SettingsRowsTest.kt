package dev.jellyboost.feature.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for what `SettingsRows.kt` decides outside of composition.
 *
 * [choiceRowDescription] is the whole of the F12 fix from the 2026-08-05 accessibility audit: a
 * choice row used to announce its own two or three words and nothing else, so the two skip-mode
 * groups were six rows reading "Off / Show button / Auto" twice over, with the words that tell them
 * apart sitting in a caption that belonged to nothing. Folding the group name into every row is the
 * only association a screen reader can rely on, and this is where the wording is settled.
 */
class SettingsRowsTest {
    @Test
    @DisplayName("a plain option leads with the group it belongs to")
    fun groupLabelComesFirst() {
        choiceRowDescription(
            groupLabel = "Skip intro",
            label = "Auto skip",
            supportingText = null,
            actionHint = null,
        ) shouldBe "Skip intro, Auto skip"
    }

    @Test
    @DisplayName("a supporting fact follows the option it qualifies")
    fun supportingTextFollowsTheLabel() {
        choiceRowDescription(
            groupLabel = "Storage location",
            label = "SD card",
            supportingText = "118 GB free",
            actionHint = null,
        ) shouldBe "Storage location, SD card, 118 GB free"
    }

    @Test
    @DisplayName("the recovery hint comes last, after everything describing the row")
    fun actionHintComesLast() {
        // The storage picker's F13 case: the row is already selected, so what a tap *does* is the
        // one thing nothing on screen says.
        choiceRowDescription(
            groupLabel = "Storage location",
            label = "Internal storage",
            supportingText = "41 GB free",
            actionHint = "Choose this one to keep downloads here",
        ) shouldBe "Storage location, Internal storage, 41 GB free, Choose this one to keep downloads here"
    }

    @Test
    @DisplayName("an absent hint leaves no dangling separator")
    fun absentPiecesLeaveNoSeparator() {
        choiceRowDescription(
            groupLabel = "Download quality",
            label = "Original",
            supportingText = null,
            actionHint = "Choose this one",
        ) shouldBe "Download quality, Original, Choose this one"
    }
}
