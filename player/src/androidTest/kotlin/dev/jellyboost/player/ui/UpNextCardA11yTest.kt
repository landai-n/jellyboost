package dev.jellyboost.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.player.R
import dev.jellyboost.player.upnext.UpNextEpisode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * Three claims, each one modifier away from being silently untrue:
 * - the card is **not** one merged node (a `semantics(mergeDescendants = true)` or a `clickable`
 *   on the container would collapse "play next" and "dismiss" into one stop offering only one);
 * - its static block *is* one stop, an authored sentence rather than fragments read in layout order;
 * - that block is a polite live region, since the time-boxed offer must announce itself to a user
 *   who isn't watching the screen.
 *
 * Device-only: Compose semantics are invisible to static lint.
 */
@RunWith(AndroidJUnit4::class)
class UpNextCardA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theCardIsTwoActionsAndOneSentence() {
        var played = 0
        var dismissed = 0
        rule.setContent {
            JellyfinTheme {
                UpNextCard(episode = EPISODE, onPlayNext = { played++ }, onDismiss = { dismissed++ })
            }
        }

        // Exactly two: the pill and the close button. A third would mean the card body had become
        // clickable, which is the merge this layout exists to avoid.
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)

        // Name-in-label vs. name-in-description: WCAG 2.5.3 is satisfied either way.
        rule.onNodeWithText(rule.activity.getString(R.string.player_up_next_play)).performClick()
        rule.waitForIdle()
        assertEquals(1, played)
        assertEquals(0, dismissed)

        rule.onNodeWithContentDescription(rule.activity.getString(R.string.player_up_next_dismiss)).performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(1, played)
    }

    @Test
    fun theStaticBlockSaysUpNextItsNumberAndItsTitleInOneBreath() {
        rule.setContent {
            JellyfinTheme {
                UpNextCard(episode = EPISODE, onPlayNext = {}, onDismiss = {})
            }
        }

        rule.onNodeWithContentDescription(spokenSentence()).assertExists()
    }

    @Test
    fun theSentenceIsAnnouncedWhenTheCardAppears() {
        rule.setContent {
            JellyfinTheme {
                UpNextCard(episode = EPISODE, onPlayNext = {}, onDismiss = {})
            }
        }

        val node = rule.onNodeWithContentDescription(spokenSentence()).fetchSemanticsNode()
        // Polite, not assertive: an offer over a film still playing, not an interruption.
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun anEpisodeWithNoNumberStillNamesItself() {
        val unnumbered = EPISODE.copy(indexNumber = null, parentIndexNumber = null)
        rule.setContent {
            JellyfinTheme {
                UpNextCard(episode = unnumbered, onPlayNext = {}, onDismiss = {})
            }
        }

        // No "S null · E null", and no empty fragment left where the number was.
        val eyebrow = rule.activity.getString(R.string.player_up_next_title)
        rule.onNodeWithContentDescription("$eyebrow, $TITLE").assertExists()
    }

    /** The card's authored description, assembled exactly as `UpNextLabel` assembles it. */
    private fun spokenSentence(): String {
        val eyebrow = rule.activity.getString(R.string.player_up_next_title)
        val number = rule.activity.getString(CoreUiR.string.media_episode_label, SEASON, NUMBER)
        return "$eyebrow, $number, $TITLE"
    }

    private companion object {
        const val TITLE = "The Bicameral Mind"
        const val SEASON = 1
        const val NUMBER = 11

        val EPISODE =
            UpNextEpisode(
                itemId = "episode-11",
                title = TITLE,
                indexNumber = NUMBER,
                parentIndexNumber = SEASON,
                // No still: the card must be readable without one, and an instrumented test has no
                // network to fetch one over.
                imageUrl = null,
            )
    }
}
