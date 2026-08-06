package dev.jellyboost.feature.music.nowplaying

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.LyricLine
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
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

    // ---- lyrics wiring (M13 Phase 6) -----------------------------------------------------------

    @Test
    fun `the current track's cached lyrics are attached, a different track's are not`() {
        val queue = listOf(track("t1", "Track 1"), track("t2", "Track 2"))
        val active =
            MusicPlaybackState.Active(
                queue = queue,
                currentIndex = 0,
                isPlaying = true,
                positionMs = 0L,
                durationMs = 0L,
                shuffleEnabled = false,
                repeatMode = MusicRepeatMode.OFF,
            )
        val lyrics = Lyrics(lines = listOf(LyricLine(startTicks = null, text = "La la la")), isSynced = false)

        val state = active.toNowPlayingUiState(lyricsByTrackId = mapOf("t1" to lyrics, "t2" to null))

        state.lyrics shouldBe lyrics
        state.lyricsAvailable shouldBe true
    }

    @Test
    fun `no cache entry for the current track reads as unavailable`() {
        val active =
            MusicPlaybackState.Active(
                queue = listOf(track("t1", "Track 1")),
                currentIndex = 0,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                shuffleEnabled = false,
                repeatMode = MusicRepeatMode.OFF,
            )

        val state = active.toNowPlayingUiState(lyricsByTrackId = emptyMap())

        state.lyrics.shouldBeNull()
        state.lyricsAvailable shouldBe false
    }

    @Test
    fun `idle carries no lyrics regardless of what the cache holds`() {
        val lyrics = Lyrics(lines = listOf(LyricLine(startTicks = null, text = "Stale")), isSynced = false)

        val state = MusicPlaybackState.Idle.toNowPlayingUiState(lyricsByTrackId = mapOf("t1" to lyrics))

        state.lyrics.shouldBeNull()
    }

    // ---- activeLyricLineIndex (M13 Phase 6) --------------------------------------------------

    @Test
    fun `before the first timed line, nothing is active`() {
        val lines = listOf(line(10_000_000L, "First"), line(20_000_000L, "Second"))

        activeLyricLineIndex(lines, positionMs = 500L).shouldBeNull()
    }

    @Test
    fun `exactly on a line's start, that line is active`() {
        val lines = listOf(line(10_000_000L, "First"), line(20_000_000L, "Second"))

        activeLyricLineIndex(lines, positionMs = 1_000L) shouldBe 0
    }

    @Test
    fun `between two lines, the earlier one stays active`() {
        val lines = listOf(line(10_000_000L, "First"), line(20_000_000L, "Second"))

        activeLyricLineIndex(lines, positionMs = 1_500L) shouldBe 0
    }

    @Test
    fun `after the last line, it stays active rather than falling off`() {
        val lines = listOf(line(10_000_000L, "First"), line(20_000_000L, "Second"))

        activeLyricLineIndex(lines, positionMs = 999_999L) shouldBe 1
    }

    @Test
    fun `an untimed line inside an otherwise synced set is skipped, not treated as starting at zero`() {
        val lines = listOf(line(10_000_000L, "First"), line(null, "Blank separator"), line(20_000_000L, "Second"))

        // Between the first and third line's timing — the untimed second line must not have reset
        // the active index back to itself (which would read as "starts at 0").
        activeLyricLineIndex(lines, positionMs = 1_500L) shouldBe 0
    }

    @Test
    fun `no lines has nothing active`() {
        activeLyricLineIndex(emptyList(), positionMs = 5_000L).shouldBeNull()
    }

    private fun line(
        startTicks: Long?,
        text: String,
    ) = LyricLine(startTicks = startTicks, text = text)

    private fun track(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.AUDIO)
}
