package dev.jellyboost.player.ui

import androidx.lifecycle.SavedStateHandle
import dev.jellyboost.player.model.PlaybackSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlayerSessionStore] — the handle the player route arrives on and writes back to.
 *
 * One rule: nav arguments say what the user *tapped* and are never overwritten; live-position
 * keys say what the session *reached* and win when present. Getting that backwards is silent and
 * costs the resume point of a half-watched film.
 */
class PlayerSessionStoreTest {
    @Test
    fun `a fresh navigation starts at the route's resume position and plays`() {
        val store = PlayerSessionStore(navArgs())

        store.itemId shouldBe ITEM_ID
        store.mediaSourceId shouldBe MEDIA_SOURCE_ID
        store.startPositionTicks shouldBe RESUME_TICKS
        store.playWhenReady shouldBe true
    }

    @Test
    fun `a restore starts where playback got to, not where the user tapped Play`() {
        val store =
            PlayerSessionStore(
                navArgs(
                    PlayerViewModel.KEY_LIVE_POSITION_TICKS to LIVE_TICKS,
                    PlayerViewModel.KEY_WAS_PLAYING to true,
                ),
            )

        store.startPositionTicks shouldBe LIVE_TICKS
    }

    @Test
    fun `a session that was paused when the process died comes back paused`() {
        val store =
            PlayerSessionStore(
                navArgs(
                    PlayerViewModel.KEY_LIVE_POSITION_TICKS to LIVE_TICKS,
                    PlayerViewModel.KEY_WAS_PLAYING to false,
                ),
            )

        store.playWhenReady shouldBe false
    }

    @Test
    fun `a route with no resume argument at all starts from the beginning`() {
        val handle = SavedStateHandle(mapOf(PlayerViewModel.ARG_ITEM_ID to ITEM_ID))

        val store = PlayerSessionStore(handle)

        store.startPositionTicks shouldBe 0L
        store.mediaSourceId.shouldBeNull()
        store.playWhenReady shouldBe true
    }

    @Test
    fun `a route with no item is a programming error, not a blank player`() {
        shouldThrow<IllegalArgumentException> { PlayerSessionStore(SavedStateHandle()) }
    }

    @Test
    fun `a tick records the position and the play state for the next process`() {
        val handle = navArgs()
        val store = PlayerSessionStore(handle)

        store.rememberLivePosition(PlaybackSnapshot(positionMs = LIVE_TICKS / 10_000L, isPlaying = true))

        handle.get<Long>(PlayerViewModel.KEY_LIVE_POSITION_TICKS) shouldBe LIVE_TICKS
        handle.get<Boolean>(PlayerViewModel.KEY_WAS_PLAYING) shouldBe true
        // The route's own argument is what the user tapped, and stays intact under it.
        handle.get<Long>(PlayerViewModel.ARG_START_TICKS) shouldBe RESUME_TICKS
    }

    @Test
    fun `position zero is not recorded over the route's resume argument`() {
        val handle = navArgs()
        val store = PlayerSessionStore(handle)

        store.rememberLivePosition(PlaybackSnapshot(positionMs = 0L, isPlaying = true))

        // Zero is indistinguishable from "no session yet" — the nav argument is the better answer.
        handle.get<Long>(PlayerViewModel.KEY_LIVE_POSITION_TICKS).shouldBeNull()
    }

    private fun navArgs(vararg extra: Pair<String, Any>) =
        SavedStateHandle(
            mapOf(
                PlayerViewModel.ARG_ITEM_ID to ITEM_ID,
                PlayerViewModel.ARG_MEDIA_SOURCE_ID to MEDIA_SOURCE_ID,
                PlayerViewModel.ARG_START_TICKS to RESUME_TICKS,
            ) + extra,
        )

    private companion object {
        const val ITEM_ID = "0b3d5f6a-1c2e-4a7b-9d8c-5e4f3a2b1c0d"
        const val MEDIA_SOURCE_ID = "source-1"
        const val RESUME_TICKS = 12_000_000_000L

        /** 90 minutes in — an hour past [RESUME_TICKS], so a stale replay is unmistakable. */
        const val LIVE_TICKS = 54_000_000_000L
    }
}
