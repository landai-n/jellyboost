package dev.jellyboost.feature.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
