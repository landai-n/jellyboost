package dev.jellyfinnative.player.model

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TrickplayTiles].
 *
 * The scrubber is a composable and untestable off a device; the part that decides *what* it draws
 * is this arithmetic, and it is the part that is easy to get wrong — a transposed column and row
 * gives a preview that is plausible, off by a few seconds, and impossible to spot by eye.
 */
class TrickplayTilesTest {
    private val tiles =
        TrickplayTiles(
            thumbnailWidth = 320,
            thumbnailHeight = 180,
            columns = 10,
            rows = 10,
            thumbnailCount = 250,
            intervalMs = 10_000,
            tileUris = listOf("https://server/t.0.jpg", "https://server/t.1.jpg", "https://server/t.2.jpg"),
        )

    @Test
    fun `addresses a thumbnail by sheet, column and row`() {
        // 23 minutes in, one thumbnail every 10 s, is thumbnail 138: sheet 1, cell 38 of 100 —
        // row 3, column 8. Column and row are not interchangeable and this is what proves it.
        val thumbnail = tiles.tileFor(positionMs = 1_380_000L).shouldNotBeNull()

        thumbnail.uri shouldBe "https://server/t.1.jpg"
        thumbnail.column shouldBe 8
        thumbnail.row shouldBe 3
    }

    @Test
    fun `the first thumbnail is the first cell of the first sheet`() {
        val thumbnail = tiles.tileFor(positionMs = 0L).shouldNotBeNull()

        thumbnail.uri shouldBe "https://server/t.0.jpg"
        thumbnail.column shouldBe 0
        thumbnail.row shouldBe 0
    }

    @Test
    fun `a position past the last thumbnail clamps to it rather than running off the sheet`() {
        // 250 thumbnails exist; hour four does not. Clamping to 249 keeps the preview on the last
        // frame instead of addressing a cell nobody generated.
        val thumbnail = tiles.tileFor(positionMs = 14_400_000L).shouldNotBeNull()

        thumbnail.uri shouldBe "https://server/t.2.jpg"
        thumbnail.column shouldBe 9
        thumbnail.row shouldBe 4
    }

    @Test
    fun `a negative position is treated as the start`() {
        tiles.tileFor(positionMs = -5_000L).shouldNotBeNull().row shouldBe 0
    }

    @Test
    fun `a sheet that is missing has nothing to draw`() {
        val incomplete = tiles.copy(tileUris = listOf("file:///t.0.jpg"))

        incomplete.tileFor(positionMs = 1_380_000L).shouldBeNull()
    }

    @Test
    fun `unusable geometry resolves to nothing rather than dividing by zero`() {
        tiles.copy(intervalMs = 0).tileFor(positionMs = 1_000L).shouldBeNull()
        tiles.copy(columns = 0).tileFor(positionMs = 1_000L).shouldBeNull()
        tiles.copy(thumbnailCount = 0).tileFor(positionMs = 1_000L).shouldBeNull()
    }

    @Test
    fun `the aspect ratio comes from the thumbnail size, and falls back when it cannot`() {
        tiles.aspectRatio shouldBe (320f / 180f plusOrMinus TOLERANCE)
        tiles.copy(thumbnailHeight = 0).aspectRatio shouldBe (16f / 9f plusOrMinus TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
