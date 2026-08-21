package dev.jellyboost.feature.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The series and season chips are two individually-focusable buttons, each with its own spoken
 * label (accessibility audit checklist: "Dynamic a11y ships with the surface" — new cards/rows
 * merge descendants with one spoken sentence, and every interactive one carries the Button role
 * rather than announcing as plain text). Modelled on [EpisodeRowA11yTest].
 */
@RunWith(AndroidJUnit4::class)
class EpisodeOriginChipsA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theTwoChipsAreTwoSeparateButtonsNotOneMergedRow() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(item = EPISODE, onNavigateToItemId = {})
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun everyChipAnnouncesTheButtonRole() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(item = EPISODE, onNavigateToItemId = {})
            }
        }

        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    fun theSeriesChipSpeaksMoreThanItsVisibleLabel() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(item = EPISODE, onNavigateToItemId = {})
            }
        }

        // The eye reads the bare series name; a screen reader is told what tapping it does.
        rule.onNodeWithText(SERIES_NAME).assertExists()
        val description = rule.activity.getString(R.string.detail_go_to_series, SERIES_NAME)
        rule.onNodeWithContentDescription(description).assertExists()
    }

    @Test
    fun theSeasonChipSpeaksWhatTappingItDoes() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(item = EPISODE, onNavigateToItemId = {})
            }
        }

        val description = rule.activity.getString(R.string.detail_go_to_season_description, SEASON_NUMBER)
        rule.onNodeWithContentDescription(description).assertExists()
    }

    private companion object {
        const val SERIES_NAME = "Westworld"
        const val SEASON_NUMBER = 1

        val EPISODE =
            JellyfinItem(
                id = "episode-10",
                name = "The Original",
                type = ItemType.EPISODE,
                seriesId = "series-1",
                seriesName = SERIES_NAME,
                seasonId = "season-1",
                parentIndexNumber = SEASON_NUMBER,
                indexNumber = 1,
            )
    }
}
