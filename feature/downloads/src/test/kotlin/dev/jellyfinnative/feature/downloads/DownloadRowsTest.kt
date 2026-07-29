package dev.jellyfinnative.feature.downloads

import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.data.downloads.model.DownloadItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the row-title formatting in `DownloadRows.kt`.
 *
 * Pins the M9 device-walk fix (docs/POLISH.md): a row drawn under its series' own group header must
 * not repeat the series name the header already shows.
 */
class DownloadRowsTest {
    @Test
    fun `an episode outside a series group shows series and title`() {
        val episode = episode(series = "Westworld", title = "Chestnut")

        episode.rowTitle() shouldBe "Westworld · Chestnut"
    }

    @Test
    fun `an episode inside its series group shows only the title`() {
        val episode = episode(series = "Pyjamasques", title = "Bibou et le ballon-lune")

        episode.rowTitle(inSeriesGroup = true) shouldBe "Bibou et le ballon-lune"
    }

    @Test
    fun `a film is unaffected by inSeriesGroup since films are never grouped`() {
        val film = episode(series = null, title = "Dune")

        film.rowTitle() shouldBe "Dune"
        film.rowTitle(inSeriesGroup = true) shouldBe "Dune"
    }

    private fun episode(
        series: String?,
        title: String,
    ) = DownloadItem(
        itemId = "1",
        title = title,
        seriesName = series,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = 0L,
        bytesTotal = 0L,
        bytesOnDisk = 0L,
        queuePosition = 0,
    )
}
