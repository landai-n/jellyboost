package dev.jellyboost.feature.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The episode page's two shortcut rows — Next episode, and More from this season — as rendered by
 * [ItemDetailContent]'s shared `LazyColumn` (both compact and wide flow through the same section
 * list, so one render suffices).
 */
@RunWith(AndroidJUnit4::class)
class EpisodeDetailSectionsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theNextEpisodeRowRendersWhenThereIsANextEpisode() {
        setContent(state = baseState().copy(nextEpisode = OTHER_EPISODE))

        val title = rule.activity.getString(R.string.detail_section_next_episode)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        rule.onNodeWithText(title).assertExists()
    }

    @Test
    fun noNextEpisodeRowWithoutANextEpisode() {
        setContent(state = baseState())

        val title = rule.activity.getString(R.string.detail_section_next_episode)
        rule.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun theSiblingsRowNamesTheSeasonWhenItHasANumber() {
        setContent(state = baseState().copy(seasonEpisodes = listOf(CURRENT_EPISODE, OTHER_EPISODE)))

        val title = rule.activity.getString(R.string.detail_section_more_from_season, SEASON_NUMBER)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        rule.onNodeWithText(title).assertExists()
    }

    @Test
    fun theSiblingsRowFallsBackToTheUnnumberedTitleWithoutASeasonNumber() {
        setContent(
            state =
                baseState(item = CURRENT_EPISODE.copy(parentIndexNumber = null))
                    .copy(seasonEpisodes = listOf(CURRENT_EPISODE.copy(parentIndexNumber = null), OTHER_EPISODE)),
        )

        val title = rule.activity.getString(R.string.detail_section_more_from_season_unnumbered)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        rule.onNodeWithText(title).assertExists()
    }

    @Test
    fun noSiblingsRowWhenTheOnlySeasonEpisodeIsTheCurrentOne() {
        setContent(state = baseState().copy(seasonEpisodes = listOf(CURRENT_EPISODE)))

        val numbered = rule.activity.getString(R.string.detail_section_more_from_season, SEASON_NUMBER)
        val unnumbered = rule.activity.getString(R.string.detail_section_more_from_season_unnumbered)
        rule.onNodeWithText(numbered).assertDoesNotExist()
        rule.onNodeWithText(unnumbered).assertDoesNotExist()
    }

    @Test
    fun noSiblingsRowWhenNoSeasonEpisodesWereFetched() {
        setContent(state = baseState())

        val numbered = rule.activity.getString(R.string.detail_section_more_from_season, SEASON_NUMBER)
        rule.onNodeWithText(numbered).assertDoesNotExist()
    }

    private fun setContent(state: ItemDetailUiState) {
        rule.setContent {
            JellyfinTheme {
                ItemDetailContent(
                    state = state,
                    onRetry = {},
                    onItemClick = {},
                    onPlay = {},
                    actions =
                        DetailActionHandlers(
                            onPlay = {},
                            onDownload = {},
                            onToggleWatched = {},
                            onToggleFavorite = {},
                        ),
                )
            }
        }
    }

    private fun baseState(item: JellyfinItem = CURRENT_EPISODE) = ItemDetailUiState(isLoading = false, item = item)

    private companion object {
        const val SEASON_NUMBER = 1

        val CURRENT_EPISODE =
            JellyfinItem(
                id = "episode-10",
                name = "The Original",
                type = ItemType.EPISODE,
                seriesId = "series-1",
                seriesName = "Westworld",
                seasonId = "season-1",
                parentIndexNumber = SEASON_NUMBER,
                indexNumber = 1,
            )

        val OTHER_EPISODE =
            JellyfinItem(
                id = "episode-11",
                name = "Chestnut",
                type = ItemType.EPISODE,
                seriesId = "series-1",
                seriesName = "Westworld",
                seasonId = "season-1",
                parentIndexNumber = SEASON_NUMBER,
                indexNumber = 2,
            )
    }
}
