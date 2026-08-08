package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DownloadProgressRatchet].
 *
 * Every case is a shape the live projection can produce and the old fixed-ceiling denominator could
 * not: a total that *grows* mid-transfer, and therefore a raw percentage that falls while the bytes
 * on disk only ever rise.
 */
class DownloadProgressRatchetTest {
    private val ratchet = DownloadProgressRatchet()

    @Test
    fun `a rising percentage is passed straight through`() {
        ratchet.update(listOf(downloading(bytes = 100L, total = 1_000L)))["1"] shouldBe 0.1f

        ratchet.update(listOf(downloading(bytes = 300L, total = 1_000L)))["1"] shouldBe 0.3f
    }

    @Test
    fun `a growing projection cannot make the bar retreat`() {
        // 50 % against a 300 MB projection…
        ratchet.update(listOf(downloading(bytes = 150L, total = 300L)))["1"] shouldBe 0.5f

        // …and then the encoder hits a busy stretch and the projection is corrected to 600 MB.
        // The raw fraction is now 25 %, and no byte was lost.
        ratchet.update(listOf(downloading(bytes = 150L, total = 600L)))["1"] shouldBe 0.5f
    }

    @Test
    fun `the highest percentage reached this session is the one that keeps being shown`() {
        ratchet.update(listOf(downloading(bytes = 800L, total = 1_000L)))
        ratchet.update(listOf(downloading(bytes = 800L, total = 4_000L)))

        // Not 20 %, and not an average of the two — the highest, because the bytes on disk are the
        // only monotone thing here and the bar claims to be about them.
        ratchet.update(listOf(downloading(bytes = 810L, total = 4_000L)))["1"] shouldBe 0.8f
    }

    @Test
    fun `a transferring item is held at ninety-nine percent however far it has run`() {
        // An estimate that undershot: more bytes have landed than the denominator expected.
        val progress = ratchet.update(listOf(downloading(bytes = 1_000L, total = 1_000L)))["1"]!!

        progress shouldBe 0.99f
    }

    @Test
    fun `only a finished download draws a full bar`() {
        ratchet.update(listOf(downloading(bytes = 1_000L, total = 1_000L)))["1"] shouldBe 0.99f

        ratchet.update(listOf(item(DownloadStatus.DOWNLOADED, bytes = 1_000L, total = 1_000L)))["1"] shouldBe 1f
    }

    @Test
    fun `a paused item keeps the height it reached rather than dropping`() {
        ratchet.update(listOf(downloading(bytes = 600L, total = 1_000L)))

        ratchet.update(listOf(item(DownloadStatus.PAUSED, bytes = 600L, total = 2_000L)))["1"] shouldBe 0.6f
    }

    @Test
    fun `a restarted transcode holds its bar instead of falling back to zero`() {
        // The server ignores `Range` on a transcode, so an interrupted one rewrites from byte zero.
        ratchet.update(listOf(downloading(bytes = 700L, total = 1_000L)))

        val afterRestart = ratchet.update(listOf(downloading(bytes = 0L, total = 1_000L)))["1"]!!

        // Deliberate: a stalled bar beats a retreating one, and the byte figure beside it is honest.
        afterRestart shouldBe 0.7f
        afterRestart shouldBeGreaterThan 0f
    }

    @Test
    fun `an item that leaves the list is forgotten, so a re-download starts over`() {
        ratchet.update(listOf(downloading(bytes = 900L, total = 1_000L)))

        // Deleted…
        ratchet.update(emptyList())["1"].shouldBeNull()

        // …and downloaded again: a fresh item, not the ghost of the old one.
        ratchet.update(listOf(downloading(bytes = 50L, total = 1_000L)))["1"] shouldBe 0.05f
    }

    @Test
    fun `each item ratchets on its own`() {
        ratchet.update(listOf(downloading(id = "1", bytes = 800L, total = 1_000L)))

        val both =
            ratchet.update(
                listOf(
                    downloading(id = "1", bytes = 800L, total = 4_000L),
                    downloading(id = "2", bytes = 100L, total = 1_000L),
                ),
            )

        both["1"] shouldBe 0.8f
        both["2"] shouldBe 0.1f
    }

    @Test
    fun `only the rows it was given are answered for`() {
        // The ViewModel passes `toQueue()`'s subset rather than the whole table (audit 2026-08-08,
        // PERF-10), so the map is the size of the queue and not of everything ever downloaded.
        val answers =
            ratchet.update(
                listOf(
                    downloading(id = "1", bytes = 100L, total = 1_000L),
                    downloading(id = "2", bytes = 500L, total = 1_000L),
                ),
            )

        answers.keys shouldBe setOf("1", "2")
    }

    @Test
    fun `a row that leaves the queue by finishing is forgotten, like one that is deleted`() {
        ratchet.update(listOf(downloading(bytes = 900L, total = 1_000L)))["1"] shouldBe 0.9f

        // `toQueue()` excludes DOWNLOADED rows, so a completed download simply stops being passed —
        // and must not leave its 90 % behind for whatever is enqueued next under the same id.
        ratchet.update(emptyList())["1"].shouldBeNull()

        ratchet.update(listOf(downloading(bytes = 10L, total = 1_000L)))["1"] shouldBe 0.01f
    }

    @Test
    fun `an unknown total reads as zero rather than as complete`() {
        ratchet.update(listOf(downloading(bytes = 500L, total = 0L)))["1"]!!.toDouble() shouldBe
            (0.0 plusOrMinus 1e-6)
    }

    private fun downloading(
        id: String = "1",
        bytes: Long,
        total: Long,
    ) = item(DownloadStatus.DOWNLOADING, bytes, total, id)

    private fun item(
        status: DownloadStatus,
        bytes: Long,
        total: Long,
        id: String = "1",
    ) = DownloadItem(
        itemId = id,
        title = "Chestnut",
        seriesName = "Westworld",
        status = status,
        bytesDownloaded = bytes,
        bytesTotal = total,
        bytesOnDisk = bytes,
        queuePosition = 0,
        quality = DownloadQuality.LOW,
    )
}
