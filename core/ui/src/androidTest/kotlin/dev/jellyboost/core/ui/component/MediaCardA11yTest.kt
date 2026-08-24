package dev.jellyboost.core.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.a11y.AccessibilityChecks
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The merged card node, held still on a device.
 *
 * This test's whole value is what a screen reader *does not* say: the artwork's
 * description, the title's own node, the subtitle's, and every badge's, all collapsed into one
 * authored sentence. A JVM test can pin the sentence — `MediaCardFactsTest` does — but only a
 * composed tree can show that the card is one stop rather than six, which
 * a careless `Modifier` change would silently undo.
 */
@RunWith(AndroidJUnit4::class)
class MediaCardA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val checks by lazy { AccessibilityChecks(rule) }

    @Before
    fun enableAccessibilityChecks() {
        checks.install()
    }

    @Test
    fun posterCardIsOneNodeThatNamesItsTypeAndTitle() {
        rule.setContent {
            JellyfinTheme {
                PosterCard(item = MOVIE, onClick = {})
            }
        }

        val movie = rule.activity.getString(R.string.media_card_type_movie)
        assertEquals("$movie, $MOVIE_TITLE, 2016", rule.onlyCardDescription())
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        // The visible title is `clearAndSetSemantics`-ed, so it contributes no second stop of its
        // own. If this starts failing, the card announces its title twice.
        rule.onAllNodesWithText(MOVIE_TITLE).assertCountEquals(0)
        checks.assertClean()
    }

    @Test
    fun theCardDescriptionCarriesTheTitleTheVisibleLineTruncates() {
        rule.setContent {
            JellyfinTheme {
                PosterCard(item = MOVIE.copy(name = LONG_TITLE), onClick = {})
            }
        }

        // `maxLines = 1` cuts the drawn title after a few words; the sentence must not be cut with
        // it, because the artwork description is the only place the full name is preserved.
        assertEquals(true, rule.onlyCardDescription().contains(LONG_TITLE))
    }

    @Test
    fun aStartedEpisodeSaysItsTypeItsNumberAndHowFarIn() {
        rule.setContent {
            JellyfinTheme {
                PosterCard(item = EPISODE, onClick = {})
            }
        }

        val episode = rule.activity.getString(R.string.media_card_type_episode)
        val progress = rule.activity.getString(R.string.media_card_progress, HALF_PERCENT)
        // Read from the resource, not spelled here: the card *speaks* the same localized
        // "S1 · E4" it *draws*. Spelling it in the test would re-create exactly the kind of drift
        // this guards against.
        val number = rule.activity.getString(R.string.media_episode_label, SEASON_NUMBER, EPISODE_NUMBER)
        assertEquals(
            "$episode, $SERIES_TITLE, $number${Separators.DOT}$EPISODE_TITLE, $progress",
            rule.onlyCardDescription(),
        )
        checks.assertClean()
    }

    @Test
    fun selectionModeIsRealSelectedSemanticsAndNotAWordInTheSentence() {
        rule.setContent {
            JellyfinTheme {
                PosterCard(item = MOVIE, onClick = {}, onLongClick = {}, selected = true)
            }
        }

        val node = rule.onNode(hasClickAction()).fetchSemanticsNode()
        assertEquals(true, node.config[SemanticsProperties.Selected])
        assertEquals(
            rule.activity.getString(R.string.selection_item_selected),
            node.config[SemanticsProperties.StateDescription],
        )
        // Selection is state, not prose: the sentence is the same one an unselected card says.
        val movie = rule.activity.getString(R.string.media_card_type_movie)
        assertEquals("$movie, $MOVIE_TITLE, 2016", node.config[SemanticsProperties.ContentDescription].single())
    }

    @Test
    fun aThumbCardInsideAClickableRowIsNotASecondStop() {
        rule.setContent {
            JellyfinTheme {
                Box(modifier = mediaCardSemantics(description = "the row")) {
                    // How `EpisodeRow` composes it: the row owns the tap, the artwork owns nothing.
                    ThumbCard(item = EPISODE, onClick = null, showTitle = false)
                }
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
        assertEquals("the row", rule.onlyCardDescription())
    }

    @Test
    fun aPosterAndAThumbAnnounceTheSameItemIdentically() {
        // The two cards were ~90% the same composable, and the 10% that differed included two
        // spellings of the same click-and-semantics block. They are one
        // `MediaCard` now; this is the property that made merging them worth doing, and the one a
        // future "just for the thumb" tweak would break silently.
        rule.setContent {
            JellyfinTheme {
                Column {
                    PosterCard(
                        item = EPISODE,
                        onClick = {},
                        onLongClick = {},
                        selected = false,
                        topStartBadge = BADGE,
                        timeChipText = TIME_LEFT,
                        ratingBadge = RATING,
                    )
                    ThumbCard(
                        item = EPISODE,
                        onClick = {},
                        onLongClick = {},
                        selected = false,
                        topStartBadge = BADGE,
                        timeChipText = TIME_LEFT,
                        ratingBadge = RATING,
                    )
                }
            }
        }

        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals("expected the two cards to be two nodes", 2, nodes.size)
        val (poster, thumb) = nodes
        assertEquals(
            poster.config[SemanticsProperties.ContentDescription],
            thumb.config[SemanticsProperties.ContentDescription],
        )
        assertEquals(poster.config[SemanticsProperties.Role], thumb.config[SemanticsProperties.Role])
        assertEquals(poster.config[SemanticsProperties.Selected], thumb.config[SemanticsProperties.Selected])
        assertEquals(
            poster.config[SemanticsProperties.StateDescription],
            thumb.config[SemanticsProperties.StateDescription],
        )
    }

    /**
     * The description of the one node the tree is supposed to contain — and a failure with a
     * readable message when it is not one node.
     */
    private fun SemanticsNodeInteractionsProvider.onlyCardDescription(): String {
        val nodes = onAllNodes(hasCardDescription()).fetchSemanticsNodes()
        assertEquals("expected exactly one described card node", 1, nodes.size)
        return nodes.single().config[SemanticsProperties.ContentDescription].single()
    }

    private fun hasCardDescription() =
        SemanticsMatcher("has a contentDescription") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
        }

    private companion object {
        const val MOVIE_TITLE = "Arrival"
        const val SERIES_TITLE = "Westworld"
        const val EPISODE_TITLE = "Dissonance Theory"
        const val LONG_TITLE = "The Assassination of Jesse James by the Coward Robert Ford"

        /** The three overlay facts, so the comparison is between two *loaded* cards. */
        const val BADGE = "S1 · E4"
        const val TIME_LEFT = "27m left"
        const val RATING = 8.4f

        /** Half of a two-hour runtime, in Jellyfin's 100-nanosecond ticks. */
        const val RUNTIME_TICKS = 72_000_000_000L
        const val HALF_POSITION_TICKS = 36_000_000_000L
        const val HALF_PERCENT = 50

        const val SEASON_NUMBER = 1
        const val EPISODE_NUMBER = 4

        val MOVIE =
            JellyfinItem(
                id = "movie-1",
                name = MOVIE_TITLE,
                type = ItemType.MOVIE,
                productionYear = 2016,
            )

        val EPISODE =
            JellyfinItem(
                id = "episode-1",
                name = EPISODE_TITLE,
                type = ItemType.EPISODE,
                seriesName = SERIES_TITLE,
                parentIndexNumber = SEASON_NUMBER,
                indexNumber = EPISODE_NUMBER,
                runTimeTicks = RUNTIME_TICKS,
                userData = UserData(playbackPositionTicks = HALF_POSITION_TICKS),
            )
    }
}
