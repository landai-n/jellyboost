package dev.jellyboost.app

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.player.cast.CastingItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

/** When the casting bar holds the chrome's bar slot, what its one button does, and who wins the slot. */
class CastingBarVisibilityTest {
    @Test
    @DisplayName("a film left on the television shows the bar, off both full-screen players")
    fun showsOffBothPlayers() {
        showsCastingBar(CASTING, onPlayer = false, onNowPlaying = false) shouldBe true
    }

    @Test
    @DisplayName("the player route hides it: that screen is the remote control")
    fun hiddenOnThePlayer() {
        showsCastingBar(CASTING, onPlayer = true, onNowPlaying = false) shouldBe false
    }

    @Test
    @DisplayName("the now-playing screen hides it, as it hides the music bar")
    fun hiddenOnNowPlaying() {
        showsCastingBar(CASTING, onPlayer = false, onNowPlaying = true) shouldBe false
    }

    @Test
    @DisplayName("nothing on the television, no bar")
    fun hiddenWithNothingCast() {
        showsCastingBar(null, onPlayer = false, onNowPlaying = false) shouldBe false
    }

    @Test
    @DisplayName("cast wins the slot: with both a queue and a film on the television, only the casting bar shows")
    fun castWinsTheSlot() {
        val castingBar = showsCastingBar(CASTING, onPlayer = false, onNowPlaying = false)

        showsMiniPlayer(activeQueue(), onPlayer = false, onNowPlaying = false, castingBarShown = castingBar) shouldBe
            false
    }

    @Test
    @DisplayName("the music bar comes back once the television has nothing of ours")
    fun musicReturnsWhenCastingEnds() {
        val castingBar = showsCastingBar(null, onPlayer = false, onNowPlaying = false)

        showsMiniPlayer(activeQueue(), onPlayer = false, onNowPlaying = false, castingBarShown = castingBar) shouldBe
            true
    }

    private fun activeQueue() =
        MusicPlaybackState.Active(
            queue = listOf(JellyfinItem(id = "t1", name = "Track 1", type = ItemType.AUDIO)),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 0L,
            durationMs = 0L,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.OFF,
        )
}

class CastingBarActionTest {
    @Test
    @DisplayName("a playing television offers Pause")
    fun playingOffersPause() {
        castingBarAction(playWhenReady = true, isBuffering = false) shouldBe CastingBarAction.PAUSE
    }

    @Test
    @DisplayName("a paused television offers Play")
    fun pausedOffersPlay() {
        castingBarAction(playWhenReady = false, isBuffering = false) shouldBe CastingBarAction.PLAY
    }

    @Test
    @DisplayName("buffering keeps a Pause action, as the player's transport does")
    fun bufferingKeepsPause() {
        castingBarAction(playWhenReady = true, isBuffering = true) shouldBe CastingBarAction.PAUSE
        castingBarAction(playWhenReady = false, isBuffering = true) shouldBe CastingBarAction.PAUSE
    }
}

class CastNotificationRouteTest {
    @Test
    @DisplayName("a film left on the television opens its player, from where the television is")
    fun opensThePlayerForTheCastItem() {
        castNotificationRoute(CASTING, playerItemId = null, signedIn = true) shouldBe
            CastNotificationRoute.OpenPlayer(
                itemId = CASTING.itemId,
                startPositionTicks = CASTING.positionTicks,
                replacePlayer = false,
            )
    }

    @Test
    @DisplayName("a player for that very film already on top is left alone, whatever the id's case")
    fun leavesTheSamePlayerAlone() {
        castNotificationRoute(CASTING, playerItemId = CASTING.itemId.uppercase(Locale.ROOT), signedIn = true) shouldBe
            CastNotificationRoute.StayPut
    }

    @Test
    @DisplayName("a player for another film is replaced, not stacked under the right one")
    fun replacesAnotherPlayer() {
        castNotificationRoute(CASTING, playerItemId = OTHER_ITEM, signedIn = true) shouldBe
            CastNotificationRoute.OpenPlayer(
                itemId = CASTING.itemId,
                startPositionTicks = CASTING.positionTicks,
                replacePlayer = true,
            )
    }

    @Test
    @DisplayName("nothing detached with a player on top: that player is the one casting, and stays")
    fun theAttachedPlayerStays() {
        castNotificationRoute(null, playerItemId = CASTING.itemId, signedIn = true) shouldBe
            CastNotificationRoute.StayPut
    }

    @Test
    @DisplayName("nothing cast at all opens Home")
    fun nothingCastOpensHome() {
        castNotificationRoute(null, playerItemId = null, signedIn = true) shouldBe CastNotificationRoute.Home
    }

    @Test
    @DisplayName("signed out, the tap only brings the app forward")
    fun signedOutStaysPut() {
        castNotificationRoute(CASTING, playerItemId = null, signedIn = false) shouldBe CastNotificationRoute.StayPut
        castNotificationRoute(null, playerItemId = null, signedIn = false) shouldBe CastNotificationRoute.StayPut
    }
}

private val CASTING =
    CastingItem(
        itemId = "0b3d5f6a-1c2e-4a7b-9d8c-5e4f3a2b1c0d",
        title = "Arrival",
        subtitle = null,
        artworkUrl = null,
        deviceName = "Living Room TV",
        isReconnecting = false,
        playWhenReady = true,
        isBuffering = false,
        positionMs = 900_000L,
        durationMs = 7_200_000L,
    )

private const val OTHER_ITEM = "9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4"
