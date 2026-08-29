package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.DownloadFixtures.fontAttachment
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.subtitleStream
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The attached fonts a transcoded download needs, because the server's re-encode drops the ones its
 * ASS sidecar names. Split from [DownloadFilePlannerTest] rather than added to it: this is a
 * self-contained branch of `plan()`, and the parent class was already at detekt's size limit.
 *
 * The two conditions are independent and both are pinned here — a transcode, *and* an ASS/SSA
 * sidecar. Keying only off the sidecar would fetch faces for an ORIGINAL download of an item with an
 * external `.ass` beside it, whose container already holds them.
 */
class DownloadFilePlannerFontsTest {
    private val urls = FakeDownloadUrlFactory()
    private val planner = DownloadFilePlanner(urls)

    @Test
    fun `a transcoded download with an ASS sidecar fetches the container's fonts`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments = listOf(fontAttachment(index = 4), fontAttachment(index = 5)),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        val item = DownloadFixtures.uuid(1)
        plan.filter { it.type == DownloadFileType.FONT }.map { it.url } shouldContainExactly
            listOf("attachment://$item/source-1/4", "attachment://$item/source-1/5")
    }

    @Test
    fun `an ORIGINAL download fetches no fonts, because it keeps the container that holds them`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments = listOf(fontAttachment(index = 4)),
                ),
                DIRECTORY,
            )

        plan.filter { it.type == DownloadFileType.FONT }.shouldBeEmpty()
    }

    @Test
    fun `an ORIGINAL download with an external ASS sidecar still fetches no fonts`() {
        // The case the quality guard exists for, and the one the test above cannot reach. An external
        // subtitle is planned as a sidecar at *every* quality, so keying the font branch off "this plan
        // has an ASS sidecar" alone would fetch a second copy of attachments that are already inside
        // the container this very plan is storing whole.
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = true)),
                    attachments = listOf(fontAttachment(index = 4)),
                ),
                DIRECTORY,
            )

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldNotBeEmpty()
        plan.filter { it.type == DownloadFileType.FONT }.shouldBeEmpty()
    }

    @Test
    fun `fonts are not fetched for a sidecar no style can name a face in`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "subrip", external = false)),
                    attachments = listOf(fontAttachment(index = 4)),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        plan.filter { it.type == DownloadFileType.FONT }.shouldBeEmpty()
    }

    @Test
    fun `cover art riding along with the fonts is left behind`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ssa", external = false)),
                    attachments =
                        listOf(
                            fontAttachment(index = 4, fileName = "cover.jpg", mimeType = "image/jpeg"),
                            fontAttachment(index = 5, fileName = "credits.txt", mimeType = "text/plain"),
                            fontAttachment(index = 6),
                        ),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        plan.filter { it.type == DownloadFileType.FONT }.map { it.streamIndex } shouldContainExactly listOf(6)
    }

    @Test
    fun `an old server's octet-stream font is still recognised, by its extension`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments =
                        listOf(fontAttachment(index = 4, fileName = "Face.otf", mimeType = "application/octet-stream")),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        plan.filter { it.type == DownloadFileType.FONT }.map { it.streamIndex } shouldContainExactly listOf(4)
    }

    @Test
    fun `a font file name cannot escape the item directory`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments = listOf(fontAttachment(index = 4, fileName = "../../etc/Face .ttf")),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        val font = plan.single { it.type == DownloadFileType.FONT }
        font.fileName shouldBe "font.4.....etcFace.ttf"
        font.fileName.shouldNotContain("/")
    }

    @Test
    fun `an overlong font file name is bounded from the tail, so the extension survives`() {
        // `takeLast`, not `take`: a name long enough to threaten ENAMETOOLONG is trimmed from the
        // front, because the end is where the extension is and the front is where the padding is.
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments = listOf(fontAttachment(index = 4, fileName = "A".repeat(500) + ".ttf")),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        val font = plan.single { it.type == DownloadFileType.FONT }
        font.fileName shouldBe "font.4.${"A".repeat(56)}.ttf"
    }

    @Test
    fun `a font file name that sanitises to nothing falls back rather than ending in a dot`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments = listOf(fontAttachment(index = 4, fileName = "///", mimeType = "font/ttf")),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        plan.single { it.type == DownloadFileType.FONT }.fileName shouldBe "font.4.font"
    }

    @Test
    fun `an attachment with no name is skipped rather than stored under one invented for it`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments = listOf(fontAttachment(index = 4, fileName = null, mimeType = "font/ttf")),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        plan.filter { it.type == DownloadFileType.FONT }.shouldBeEmpty()
    }

    @Test
    fun `no font is essential`() {
        val plan =
            planner.plan(
                movie(
                    streams = listOf(subtitleStream(index = 3, codec = "ass", external = false)),
                    attachments = listOf(fontAttachment(index = 4)),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        plan.filter { it.type == DownloadFileType.FONT }.forEach { it.essential shouldBe false }
    }

    private companion object {
        const val DIRECTORY = "Arrival (2016)"
    }
}
