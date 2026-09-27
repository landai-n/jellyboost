package dev.jellyboost.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.player.cast.CastingItem
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.jellyboost.player.R as PlayerR

/**
 * The casting bar's spoken shape: one sentence for the row, one separate button, and the two
 * dynamic states — buffering and reconnecting — announced as they appear. None of it is visible to
 * lint; this is the gate.
 */
@RunWith(AndroidJUnit4::class)
class CastingBarSemanticsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(CASTING)

    /** Resolved, not literal: the device may not be in English. */
    private fun text(
        id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    private val pause get() = text(R.string.mini_player_pause)

    private fun sentence(statusId: Int) = text(R.string.casting_bar_description, TITLE, text(statusId, DEVICE))

    @Before
    fun composeTheBar() {
        rule.setContent {
            JellyfinTheme {
                CastingBar(state = state, onTogglePlayPause = {}, onClick = {})
            }
        }
    }

    @Test
    fun theRowIsOneSentenceWithItsTapAndTheTextsAreNotReadTwice() {
        rule
            .onNode(hasContentDescription(sentence(PlayerR.string.player_casting_to)) and hasClickAction())
            .assertExists()
        // The title and status lines are drawn, but only the row's sentence speaks them.
        assertEquals(0, rule.onAllNodesWithText(TITLE, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun thePlayPauseButtonIsItsOwnStop() {
        rule.onNodeWithContentDescription(pause).assertExists()
    }

    @Test
    fun bufferingKeepsPauseAndSaysSoPolitely() {
        state = CASTING.copy(isBuffering = true)
        rule.waitForIdle()

        val config = rule.onNodeWithContentDescription(pause).fetchSemanticsNode().config
        assertEquals(text(PlayerR.string.player_buffering), config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(LiveRegionMode.Polite, config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun reconnectingIsAnnouncedPolitely() {
        state = CASTING.copy(isReconnecting = true)
        rule.waitForIdle()

        val config =
            rule
                .onNode(hasContentDescription(sentence(PlayerR.string.player_cast_reconnecting)) and hasClickAction())
                .fetchSemanticsNode()
                .config
        assertEquals(LiveRegionMode.Polite, config.getOrNull(SemanticsProperties.LiveRegion))
    }

    private companion object {
        const val TITLE = "Arrival"
        const val DEVICE = "Living Room TV"

        val CASTING =
            CastingItem(
                itemId = "0b3d5f6a-1c2e-4a7b-9d8c-5e4f3a2b1c0d",
                title = TITLE,
                subtitle = null,
                artworkUrl = null,
                deviceName = DEVICE,
                isReconnecting = false,
                playWhenReady = true,
                isBuffering = false,
                positionMs = 900_000L,
                durationMs = 7_200_000L,
            )
    }
}
