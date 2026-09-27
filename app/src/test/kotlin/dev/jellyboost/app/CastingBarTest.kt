package dev.jellyboost.app

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.player.cast.CastingItem
import dev.jellyboost.player.session.tapPlays
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

    @Test
    @DisplayName("a television settled paused under a stale play intent offers Play, since the tap plays it")
    fun staleIntentOffersPlay() {
        castingBarAction(playWhenReady = true, isBuffering = false, isSettledPaused = true) shouldBe
            CastingBarAction.PLAY
    }

    @Test
    @DisplayName("the label is the tap's own rule in every state the bar can be in")
    fun labelFollowsTheTap() {
        for (playWhenReady in listOf(true, false)) {
            for (settled in listOf(true, false)) {
                val expected = if (tapPlays(playWhenReady, settled)) CastingBarAction.PLAY else CastingBarAction.PAUSE
                castingBarAction(playWhenReady, isBuffering = false, isSettledPaused = settled) shouldBe expected
            }
        }
    }

    @Test
    @DisplayName("a receiver letting go of the film offers Play that opens the player, whatever its intent says")
    fun letGoOpensThePlayer() {
        for (playWhenReady in listOf(true, false)) {
            for (buffering in listOf(true, false)) {
                for (settled in listOf(true, false)) {
                    castingBarAction(playWhenReady, buffering, settled, receiverLetGo = true) shouldBe
                        CastingBarAction.OPEN_PLAYER
                }
            }
        }
    }

    @Test
    @DisplayName("the bar's own item, letting go, is labelled from the flag CastNowPlaying sets")
    fun letGoItemLabel() {
        val item = CASTING.copy(playWhenReady = true, receiverLetGo = true)

        castingBarAction(item.playWhenReady, item.isBuffering, item.isSettledPaused, item.receiverLetGo) shouldBe
            CastingBarAction.OPEN_PLAYER
    }
}

class CastingStoppedTest {
    @Test
    @DisplayName("the television dropping the film, or the session ending, is announced with the device")
    fun announcedWhenTheItemGoes() {
        castingStopped(previous = CASTING, current = null, onPlayer = false) shouldBe
            CastingStopped(deviceName = "Living Room TV")
    }

    @Test
    @DisplayName("an unnamed receiver is still announced; the copy names it generically")
    fun unnamedDevice() {
        castingStopped(previous = CASTING.copy(deviceName = null), current = null, onPlayer = false) shouldBe
            CastingStopped(deviceName = null)
    }

    @Test
    @DisplayName("opening the player takes the bar away silently: that screen is now the remote control")
    fun silentWhenThePlayerOpens() {
        castingStopped(previous = CASTING, current = null, onPlayer = true) shouldBe null
    }

    @Test
    @DisplayName("a bar that stays, changes, or appears is not an exit")
    fun noExitNoAnnouncement() {
        val moved = CASTING.copy(positionMs = 905_000L)
        castingStopped(previous = CASTING, current = moved, onPlayer = false) shouldBe null
        castingStopped(previous = null, current = CASTING, onPlayer = false) shouldBe null
        castingStopped(previous = null, current = null, onPlayer = false) shouldBe null
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
