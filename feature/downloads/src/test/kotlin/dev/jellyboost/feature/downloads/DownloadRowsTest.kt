package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.SizeCertainty
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

    // ---- time remaining (ETA), derived from speed + displayTotalBytes ----------------------------

    @Test
    fun `no speed sample yet means no ETA`() {
        val item = film(bytesDownloaded = 100L, bytesTotal = 552L)

        item.etaSeconds(null) shouldBe null
    }

    @Test
    fun `a stalled transfer shows no ETA rather than a division by zero`() {
        val item = film(bytesDownloaded = 100L, bytesTotal = 552L)

        item.etaSeconds(0L) shouldBe null
    }

    @Test
    fun `an unknown total means no ETA, since there is nothing to count down to`() {
        val item = film(bytesDownloaded = 0L, bytesTotal = 0L)

        item.etaSeconds(10L) shouldBe null
    }

    @Test
    fun `a row already at its own total shows no ETA`() {
        val item = film(bytesDownloaded = 552L, bytesTotal = 552L)

        item.etaSeconds(10L) shouldBe null
    }

    @Test
    fun `a projection clamped up to bytes already on disk leaves nothing remaining`() {
        // displayTotalBytes clamps the projection into [bytesDownloaded, ceiling] (see its own doc),
        // so a row where the projection undershot what has already landed reports zero remaining —
        // the same clamp that stops the progress bar running past its own end also has to stop the
        // ETA going negative.
        val item = film(bytesDownloaded = 400L, bytesTotal = 552L, projected = 301L, quality = DownloadQuality.LOW)

        item.etaSeconds(10L) shouldBe null
    }

    @Test
    fun `an exact division gives a whole number of seconds`() {
        val item = film(bytesDownloaded = 452L, bytesTotal = 552L) // 100 remaining

        item.etaSeconds(10L) shouldBe 10L
    }

    @Test
    fun `a division with a remainder rounds up, never short`() {
        val item = film(bytesDownloaded = 451L, bytesTotal = 552L) // 101 remaining

        // 101 / 10 = 10.1 s, which must read as 11 s, not 10 s: an ETA that undershoots is the one
        // shape of wrong a "time remaining" figure cannot afford.
        item.etaSeconds(10L) shouldBe 11L
    }

    @Test
    fun `an estimate exactly at the 24-hour guard is still shown`() {
        val item = film(bytesDownloaded = 0L, bytesTotal = 86_400L)

        item.etaSeconds(1L) shouldBe 86_400L
    }

    @Test
    fun `an estimate beyond 24 hours is guarded out as guesswork, not shown`() {
        val item = film(bytesDownloaded = 0L, bytesTotal = 86_401L)

        item.etaSeconds(1L) shouldBe null
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

    // ---- which of Pause / Resume a queue row offers ----------------------------------------------
    // The same two predicates decide the row's buttons and the queue tab's *Pause all* / *Resume
    // all* targets, so a bulk action can never act on something its own row refuses to.

    @Test
    fun `a transferring original row is a pause target and not a resume target`() {
        val row = film(quality = DownloadQuality.ORIGINAL, status = DownloadStatus.DOWNLOADING)

        row.isPauseTarget shouldBe true
        row.isResumeTarget shouldBe false
    }

    @Test
    fun `a waiting original row is a pause target too`() {
        // Pausing a queued row is a real operation: it takes the row out of the worker's way.
        film(quality = DownloadQuality.ORIGINAL, status = DownloadStatus.QUEUED).isPauseTarget shouldBe true
    }

    @Test
    fun `a transferring transcode is no pause target`() {
        film(quality = DownloadQuality.LOW, status = DownloadStatus.DOWNLOADING).isPauseTarget shouldBe false
    }

    @Test
    fun `paused and failed rows are resume targets, transcoded or not`() {
        for (status in listOf(DownloadStatus.PAUSED, DownloadStatus.ERROR)) {
            for (quality in listOf(DownloadQuality.ORIGINAL, DownloadQuality.LOW)) {
                val row = film(quality = quality, status = status)
                row.isResumeTarget shouldBe true
                // Resume, never pause: a paused row has nothing left to pause.
                row.isPauseTarget shouldBe false
            }
        }
    }

    // ---- where a tap on the row starts playback (M10 device walk) --------------------------------
    //
    // A completed row is now clickable to play (see `DownloadedRow`'s `onPlay`), which the screen
    // wires as `onPlay(item.itemId, item.playbackStartTicks)`. There is no Compose click-simulation
    // harness in this repo (no Robolectric / androidTest here — every other screen's click wiring is
    // pinned the same way, at the pure function feeding the callback's arguments; see
    // `:feature:detail`'s `ItemDetailViewModelTest`'s "what Play actually plays" block for the
    // precedent this mirrors). What is pinned here is that function, `DownloadItem.playbackStartTicks`,
    // which is the only thing that decides *where* the play the row triggers actually starts.

    @Test
    fun `a row partway through resumes exactly where it left off`() {
        val item = film(playbackPositionTicks = 36_000_000_000L, played = false)

        item.playbackStartTicks shouldBe 36_000_000_000L
    }

    @Test
    fun `a fully watched row restarts from the beginning, not its old position`() {
        // Mutation check: if `playbackStartTicks` used `playbackPositionTicks > 0L` alone (dropping
        // the `!played` half of `isResumable`), this would wrongly resume a finished film instead of
        // restarting it.
        val item = film(playbackPositionTicks = 36_000_000_000L, played = true)

        item.playbackStartTicks shouldBe 0L
    }

    @Test
    fun `a never-started row plays from the beginning`() {
        val item = film(playbackPositionTicks = 0L, played = false)

        item.playbackStartTicks shouldBe 0L
    }

    @Test
    fun `a row whose cached item was wiped still plays, from the beginning`() {
        // The cache row and the item row are written together at enqueue time (DownloadItem's own
        // class doc), but a wiped cache must degrade to "plays from zero", not to a crash.
        val item = film(playbackPositionTicks = 36_000_000_000L, played = false).copy(item = null)

        item.playbackStartTicks shouldBe 0L
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

    // ---- the percentage a queue row announces (accessibility audit 2026-08-05, F7) ---------------

    @Test
    fun `a fraction becomes the whole percentage a screen reader says`() {
        percentOf(0f) shouldBe 0
        percentOf(0.452f) shouldBe 45
        percentOf(1f) shouldBe 100
    }

    @Test
    fun `the percentage rounds rather than truncating`() {
        // 0.455 is 45.5%, which is nearer 46 than 45 — a truncating cast would say 45 all the way
        // to 46%, so a row would sit on the same number for twice as long as any other.
        percentOf(0.455f) shouldBe 46
    }

    @Test
    fun `a fraction past its own total is clamped, never announced above a hundred`() {
        // Reachable: the ratcheted fraction is computed against a *projected* total, and a
        // projection that comes in low leaves bytes-on-disk over it for a frame.
        percentOf(1.4f) shouldBe 100
        percentOf(-0.2f) shouldBe 0
    }

    @Suppress("LongParameterList")
    private fun film(
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 552L,
        projected: Long? = null,
        sizeIsExact: Boolean = false,
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
        playbackPositionTicks: Long = 0L,
        played: Boolean = false,
    ) = episode(
        series = null,
        title = "Dune",
        quality = quality,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        projected = projected,
        sizeIsExact = sizeIsExact,
        status = status,
        playbackPositionTicks = playbackPositionTicks,
        played = played,
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
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
        playbackPositionTicks: Long = 0L,
        played: Boolean = false,
    ) = DownloadItem(
        itemId = "1",
        title = title,
        seriesName = series,
        status = status,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        bytesOnDisk = 0L,
        queuePosition = 0,
        quality = quality,
        projectedBytes = projected,
        sizeIsExact = sizeIsExact,
        item =
            JellyfinItem(
                id = "1",
                name = title,
                type = ItemType.MOVIE,
                userData = UserData(playbackPositionTicks = playbackPositionTicks, played = played),
            ),
    )
}
