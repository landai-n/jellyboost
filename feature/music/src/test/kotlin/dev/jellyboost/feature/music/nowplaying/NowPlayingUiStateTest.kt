package dev.jellyboost.feature.music.nowplaying

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for [toNowPlayingUiState] — the pure queue-state-to-screen-state mapping. */
class NowPlayingUiStateTest {
    @Test
    fun `idle maps to an idle state with no track`() {
        val state = MusicPlaybackState.Idle.toNowPlayingUiState()

        state.isIdle shouldBe true
        state.track shouldBe null
        state.queue shouldContainExactly emptyList()
    }

    @Test
    fun `active maps every field straight through and picks the track at currentIndex`() {
        val queue = listOf(track("t1", "Track 1"), track("t2", "Track 2"))
        val active =
            MusicPlaybackState.Active(
                queue = queue,
                currentIndex = 1,
                isPlaying = true,
                positionMs = 42_000L,
                durationMs = 180_000L,
                shuffleEnabled = true,
                repeatMode = MusicRepeatMode.ONE,
            )

        val state = active.toNowPlayingUiState()

        state.isIdle shouldBe false
        state.track?.id shouldBe "t2"
        state.queue shouldContainExactly queue
        state.currentIndex shouldBe 1
        state.isPlaying shouldBe true
        state.positionMs shouldBe 42_000L
        state.durationMs shouldBe 180_000L
        state.shuffleEnabled shouldBe true
        state.repeatMode shouldBe MusicRepeatMode.ONE
    }

    @Test
    fun `a favourite override patches the matching queue item without touching the others`() {
        val queue = listOf(track("t1", "Track 1"), track("t2", "Track 2"))
        val active =
            MusicPlaybackState.Active(
                queue = queue,
                currentIndex = 0,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                shuffleEnabled = false,
                repeatMode = MusicRepeatMode.OFF,
            )

        val state = active.toNowPlayingUiState(favoriteOverrides = mapOf("t1" to UserData(isFavorite = true)))

        state.track?.userData?.isFavorite shouldBe true
        state.queue[1].userData.isFavorite shouldBe false
    }

    private fun track(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.AUDIO)
}
