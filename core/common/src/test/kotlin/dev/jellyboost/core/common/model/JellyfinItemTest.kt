package dev.jellyboost.core.common.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for the display and progress logic [JellyfinItem] exposes to the cards. */
class JellyfinItemTest {
    @Test
    fun `an episode leads with its series name and labels the episode`() {
        val episode =
            JellyfinItem(
                id = "1",
                name = "Trompe L'Oeil",
                type = ItemType.EPISODE,
                seriesName = "Westworld",
                parentIndexNumber = 1,
                indexNumber = 7,
            )

        episode.displayTitle shouldBe "Westworld"
        episode.episodeLabel shouldBe "S1:E7"
        episode.displaySubtitle shouldBe "S1:E7 · Trompe L'Oeil"
    }

    @Test
    fun `an episode without a series name falls back to its own name`() {
        val episode = JellyfinItem(id = "1", name = "Pilot", type = ItemType.EPISODE)

        episode.displayTitle shouldBe "Pilot"
        episode.episodeLabel.shouldBeNull()
    }

    @Test
    fun `an episode with no season number still shows the episode number`() {
        val episode = JellyfinItem(id = "1", name = "Pilot", type = ItemType.EPISODE, indexNumber = 3)

        episode.episodeLabel shouldBe "E3"
    }

    @Test
    fun `a movie shows its production year as the subtitle`() {
        val movie = JellyfinItem(id = "1", name = "Dune", type = ItemType.MOVIE, productionYear = 2021)

        movie.displayTitle shouldBe "Dune"
        movie.displaySubtitle shouldBe "2021"
    }

    @Test
    fun `a season shows the series it belongs to`() {
        val season = JellyfinItem(id = "1", name = "Season 2", type = ItemType.SEASON, seriesName = "Westworld")

        season.displaySubtitle shouldBe "Westworld"
    }

    @Test
    fun `playback progress prefers the server-supplied percentage`() {
        val item =
            JellyfinItem(
                id = "1",
                name = "Dune",
                type = ItemType.MOVIE,
                runTimeTicks = 100L,
                userData = UserData(playbackPositionTicks = 10L, playedPercentage = 42.0),
            )

        item.playbackProgress shouldBe 0.42f
    }

    @Test
    fun `playback progress falls back to position over runtime`() {
        val item =
            JellyfinItem(
                id = "1",
                name = "Dune",
                type = ItemType.MOVIE,
                runTimeTicks = 200L,
                userData = UserData(playbackPositionTicks = 50L),
            )

        item.playbackProgress shouldBe 0.25f
    }

    @Test
    fun `playback progress is null for an item that was never started`() {
        val item = JellyfinItem(id = "1", name = "Dune", type = ItemType.MOVIE, runTimeTicks = 200L)

        item.playbackProgress.shouldBeNull()
    }

    @Test
    fun `playback progress is null when the runtime is unknown`() {
        val item =
            JellyfinItem(
                id = "1",
                name = "Dune",
                type = ItemType.MOVIE,
                userData = UserData(playbackPositionTicks = 50L),
            )

        item.playbackProgress.shouldBeNull()
    }

    @Test
    fun `an item is resumable only while partially watched`() {
        UserData(playbackPositionTicks = 50L).isResumable shouldBe true
        UserData(playbackPositionTicks = 50L, played = true).isResumable shouldBe false
        UserData().isResumable shouldBe false
    }

    @Test
    fun `only movies and episodes are playable`() {
        ItemType.MOVIE.isPlayable shouldBe true
        ItemType.EPISODE.isPlayable shouldBe true
        ItemType.SERIES.isPlayable shouldBe false
        ItemType.COLLECTION_FOLDER.isPlayable shouldBe false
    }

    @Test
    fun `download states report whether they occupy the queue`() {
        DownloadState.NotDownloaded.isActive shouldBe false
        DownloadState.Downloaded.isActive shouldBe false
        DownloadState.Failed.isActive shouldBe false
        DownloadState.Queued.isActive shouldBe true
        DownloadState.Paused.isActive shouldBe true
        DownloadState.Downloading(0.5f).isActive shouldBe true
    }

    // ---- M4: detail metadata ------------------------------------------------------------------

    @Test
    fun `converts a tick runtime into whole minutes`() {
        // 116 minutes: Jellyfin counts in 100-nanosecond ticks.
        val movie = JellyfinItem(id = "1", name = "Arrival", type = ItemType.MOVIE, runTimeTicks = 69_600_000_000L)

        movie.runtimeMinutes shouldBe 116
    }

    @Test
    fun `reports no runtime when the server reports none or zero`() {
        JellyfinItem(id = "1", name = "x", type = ItemType.MOVIE).runtimeMinutes.shouldBeNull()
        JellyfinItem(id = "1", name = "x", type = ItemType.MOVIE, runTimeTicks = 0L).runtimeMinutes.shouldBeNull()
    }

    @Test
    fun `reports the remaining minutes only while an item is resumable`() {
        val runtime = 60_000_000_000L
        val halfway =
            JellyfinItem(
                id = "1",
                name = "x",
                type = ItemType.MOVIE,
                runTimeTicks = runtime,
                userData = UserData(playbackPositionTicks = runtime / 2),
            )

        halfway.remainingMinutes shouldBe 50

        // Watched and never-started items have nothing left to report.
        halfway.copy(userData = UserData(played = true)).remainingMinutes.shouldBeNull()
        halfway.copy(userData = UserData()).remainingMinutes.shouldBeNull()
    }

    @Test
    fun `filter options count their active facets`() {
        FilterOptions().isEmpty shouldBe true
        FilterOptions().activeCount shouldBe 0

        val filters = FilterOptions(genres = listOf("Drama"), isPlayed = false)
        filters.isEmpty shouldBe false
        filters.activeCount shouldBe 2
    }
}
