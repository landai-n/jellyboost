package dev.jellyboost.feature.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavioural tests for [EpisodeOriginChips] — the series/season shortcuts under an episode page's
 * title lockup (episode-detail-shortcuts, DECISIONS.md). See [EpisodeOriginChipsA11yTest] for the
 * accessibility-specific coverage.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeOriginChipsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothChipsRenderAndNavigateToTheirOwnId() {
        var navigatedTo: String? = null
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(item = EPISODE, onNavigateToItemId = { navigatedTo = it })
            }
        }

        val seriesDescription = rule.activity.getString(R.string.detail_go_to_series, SERIES_NAME)
        rule.onNodeWithContentDescription(seriesDescription).performClick()
        assertEquals(SERIES_ID, navigatedTo)

        val seasonDescription = rule.activity.getString(R.string.detail_go_to_season_description, SEASON_NUMBER)
        rule.onNodeWithContentDescription(seasonDescription).performClick()
        assertEquals(SEASON_ID, navigatedTo)
    }

    @Test
    fun noChipsForAMovie() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(
                    item = EPISODE.copy(type = ItemType.MOVIE),
                    onNavigateToItemId = {},
                )
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun noChipsWhenNeitherTargetIsKnown() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(
                    item =
                        EPISODE.copy(
                            seriesId = null,
                            seriesName = null,
                            seasonId = null,
                            parentIndexNumber = null,
                        ),
                    onNavigateToItemId = {},
                )
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun onlyTheSeriesChipWhenTheSeasonIsUnknown() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(
                    item = EPISODE.copy(seasonId = null, parentIndexNumber = null),
                    onNavigateToItemId = {},
                )
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        val seriesDescription = rule.activity.getString(R.string.detail_go_to_series, SERIES_NAME)
        rule.onNodeWithContentDescription(seriesDescription).assertExists()
    }

    @Test
    fun onlyTheSeasonChipWhenTheSeriesIsUnknown() {
        rule.setContent {
            JellyfinTheme {
                EpisodeOriginChips(
                    item = EPISODE.copy(seriesId = null, seriesName = null),
                    onNavigateToItemId = {},
                )
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        val seasonDescription = rule.activity.getString(R.string.detail_go_to_season_description, SEASON_NUMBER)
        rule.onNodeWithContentDescription(seasonDescription).assertExists()
    }

    private companion object {
        const val SERIES_ID = "series-1"
        const val SERIES_NAME = "Westworld"
        const val SEASON_ID = "season-1"
        const val SEASON_NUMBER = 1

        val EPISODE =
            JellyfinItem(
                id = "episode-10",
                name = "The Original",
                type = ItemType.EPISODE,
                seriesId = SERIES_ID,
                seriesName = SERIES_NAME,
                seasonId = SEASON_ID,
                parentIndexNumber = SEASON_NUMBER,
                indexNumber = 1,
            )
    }
}
