package dev.jellyfinnative.feature.downloads

import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.SizeCertainty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for what `DownloadRows.kt` draws.
 *
 * Two things live here. The row-title rules pin the M9 device-walk fix (docs/POLISH.md): a row drawn
 * under its series' own group header must not repeat the series name the header already shows.
 *
 * The rest pin **which size a queue row shows, and how it is worded**. `statusLine` and
 * `expectedSizeText` are `@Composable`, so what is asserted here is the pair of values they branch
 * on: `DownloadItem.sizeCertainty` picks one of three strings (`"X"` / `"~X"` / `"up to X"`) and
 * `DownloadItem.displayTotalBytes` is the figure that goes in it. Every wording the screen can
 * produce is one of these tests.
 */
class DownloadRowsTest {
    // ---- which size is shown (schema v6) ---------------------------------------------------------

    @Test
    fun `a row with no projection is divided by the ceiling, as it always was`() {
        val item = film(bytesDownloaded = 100L, bytesTotal = 552L, quality = DownloadQuality.LOW)

        item.displayTotalBytes shouldBe 552L
    }

    @Test
    fun `a projection replaces the ceiling as the denominator`() {
        val item =
            film(bytesDownloaded = 100L, bytesTotal = 552L, projected = 301L, quality = DownloadQuality.LOW)

        // The whole point of the feature: 552 MB was never going to be the answer.
        item.displayTotalBytes shouldBe 301L
    }

    @Test
    fun `a projection is never allowed below the bytes already on disk`() {
        val item =
            film(bytesDownloaded = 400L, bytesTotal = 552L, projected = 301L, quality = DownloadQuality.LOW)

        // A denominator under the numerator would draw a bar past its own end.
        item.displayTotalBytes shouldBe 400L
        item.progress shouldBe 1f
    }

    @Test
    fun `a projection is never allowed above the ceiling`() {
        val item =
            film(bytesDownloaded = 100L, bytesTotal = 552L, projected = 900L, quality = DownloadQuality.LOW)

        item.displayTotalBytes shouldBe 552L
    }

    @Test
    fun `progress is measured against the projection, not the ceiling`() {
        val item =
            film(bytesDownloaded = 150L, bytesTotal = 600L, projected = 300L, quality = DownloadQuality.LOW)

        // 25 % against the ceiling, 50 % against what the file is actually going to be.
        item.progress shouldBe 0.5f
    }

    // ---- how it is worded — the four states a queue row can be in --------------------------------

    @Test
    fun `an original download states its size plainly, because the server measured it`() {
        val item = film(quality = DownloadQuality.ORIGINAL)

        item.sizeCertainty shouldBe SizeCertainty.EXACT
    }

    @Test
    fun `a transcode the server will stream-copy also states its size plainly`() {
        val item = film(quality = DownloadQuality.HIGH, sizeIsExact = true)

        // `allowVideoStreamCopy=true` matched: the output is the source's video plus one AAC track,
        // which is arithmetic rather than a guess (`DownloadEnqueuer.remuxBytes`).
        item.sizeCertainty shouldBe SizeCertainty.EXACT
    }

    @Test
    fun `a transcode with a projection hedges the figure rather than promising it`() {
        val item = film(quality = DownloadQuality.LOW, projected = 301L)

        // "~301 MB": measured from the stream, and still moving.
        item.sizeCertainty shouldBe SizeCertainty.APPROXIMATE
    }

    @Test
    fun `a transcode with nothing but its bound can only state a ceiling`() {
        val item = film(quality = DownloadQuality.LOW)

        // Today's behaviour, unchanged, for the opening moments of every re-encode: "up to 552 MB".
        item.sizeCertainty shouldBe SizeCertainty.CEILING
    }

    @Test
    fun `a projection on an exact row cannot downgrade it to an approximation`() {
        val item = film(quality = DownloadQuality.HIGH, sizeIsExact = true, projected = 301L)

        // The queue does not project an exact row, but the precedence has to be stated somewhere:
        // an arithmetic answer outranks a measured one.
        item.sizeCertainty shouldBe SizeCertainty.EXACT
    }

    // ---- whether the row offers Pause -------------------------------------------------------------

    @Test
    fun `an original download offers Pause, because its resume costs only the missing bytes`() {
        film(quality = DownloadQuality.ORIGINAL).isPausable shouldBe true
    }

    @Test
    fun `a transcoded download offers no Pause, because pausing one throws the transfer away`() {
        // `/Videos/{id}/stream.mkv?static=false` ignores `Range`, so there is no resume to pause
        // into: the next attempt restarts from zero. Cancel remains, and is honest about it.
        for (quality in listOf(DownloadQuality.LOW, DownloadQuality.MEDIUM, DownloadQuality.HIGH)) {
            film(quality = quality).isPausable shouldBe false
        }
    }

    // ---- row titles (M9 device walk, docs/POLISH.md) ---------------------------------------------

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

    private fun film(
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 552L,
        projected: Long? = null,
        sizeIsExact: Boolean = false,
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
    ) = episode(
        series = null,
        title = "Dune",
        quality = quality,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        projected = projected,
        sizeIsExact = sizeIsExact,
    )

    @Suppress("LongParameterList")
    private fun episode(
        series: String?,
        title: String,
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        projected: Long? = null,
        sizeIsExact: Boolean = false,
    ) = DownloadItem(
        itemId = "1",
        title = title,
        seriesName = series,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        bytesOnDisk = 0L,
        queuePosition = 0,
        quality = quality,
        projectedBytes = projected,
        sizeIsExact = sizeIsExact,
    )
}
