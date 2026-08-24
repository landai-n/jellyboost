package dev.jellyboost.feature.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * An episode row is one stop plus its Play button.
 *
 * Two stops would make the first of them useless: a nested clickable `ThumbCard` announcing a title
 * and offering the row's own action. A `null` `onClick` threaded into the artwork is what keeps the
 * row a single node — which is a one-character change in the other direction, and nothing visible
 * would move if it were made.
 *
 * The Play button is deliberately *not* folded in: it does something different from the row, and a
 * screen reader user has to be able to choose between "open this episode" and "play it".
 */
@RunWith(AndroidJUnit4::class)
class EpisodeRowA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theRowIsOneStopAndThePlayButtonIsTheOther() {
        var opened = 0
        var played = 0
        rule.setContent {
            JellyfinTheme {
                EpisodeRow(episode = EPISODE, onClick = { opened++ }, onPlay = { played++ })
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(2)

        val play = rule.activity.getString(R.string.detail_play_episode)
        rule.onNodeWithContentDescription(play).performClick()
        rule.waitForIdle()
        assertEquals(1, played)
        assertEquals(0, opened)
    }

    @Test
    fun theRowSaysItsNumberItsTitleItsRuntimeAndHowFarIn() {
        rule.setContent {
            JellyfinTheme {
                EpisodeRow(episode = EPISODE, onClick = {}, onPlay = {})
            }
        }

        val number = rule.activity.getString(R.string.detail_episode_number, EPISODE_NUMBER)
        val runtime = rule.activity.getString(R.string.detail_runtime_minutes, RUNTIME_MINUTES)
        val progress = rule.activity.getString(CoreUiR.string.media_card_progress, HALF_PERCENT)
        val spoken = "$number, $EPISODE_TITLE, $runtime, $progress"
        rule.onNodeWithContentDescription(spoken).assertExists()

        // The synopsis is drawn, and it does merge into the row's node as *text* — but the row
        // carries a `contentDescription`, and a description is what a screen reader speaks when a
        // node has both. So the claim to pin is about the sentence, not about the node's existence:
        // two lines of prose read out before the user can reach the next episode is a list nobody
        // can scan.
        val description =
            rule
                .onNodeWithContentDescription(spoken)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .single()
        assertFalse("the synopsis must stay out of the row's sentence", description.contains(SYNOPSIS))
    }

    @Test
    fun theNestedArtworkContributesNoSecondTitleNode() {
        rule.setContent {
            JellyfinTheme {
                EpisodeRow(episode = EPISODE, onClick = {}, onPlay = {})
            }
        }

        // `ThumbCard(onClick = null)` is `clearAndSetSemantics {}`: no name of its own to merge
        // into the row's authored sentence, and no click node beside the row's.
        val play = rule.activity.getString(R.string.detail_play_episode)
        val playNode = rule.onNodeWithContentDescription(play).fetchSemanticsNode()
        assertNull(playNode.config.getOrNull(SemanticsProperties.Selected))
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    private companion object {
        const val EPISODE_TITLE = "The Bicameral Mind"
        const val SYNOPSIS = "Dolores discovers her true identity, and Maeve makes her escape."
        const val EPISODE_NUMBER = 10
        const val RUNTIME_MINUTES = 90
        const val HALF_PERCENT = 50

        /** 90 minutes in Jellyfin's 100-nanosecond ticks, and half of it. */
        const val RUNTIME_TICKS = 54_000_000_000L
        const val HALF_POSITION_TICKS = 27_000_000_000L

        val EPISODE =
            JellyfinItem(
                id = "episode-10",
                name = EPISODE_TITLE,
                type = ItemType.EPISODE,
                overview = SYNOPSIS,
                seriesName = "Westworld",
                parentIndexNumber = 1,
                indexNumber = EPISODE_NUMBER,
                runTimeTicks = RUNTIME_TICKS,
                userData = UserData(playbackPositionTicks = HALF_POSITION_TICKS),
            )
    }
}
