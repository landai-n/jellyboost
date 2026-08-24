package dev.jellyboost.data.downloads.engine

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The arithmetic between [MkvClusterScanner]'s media clock and the figure the Downloads screen shows.
 * The scanner is mocked on purpose — its parsing is pinned by [MkvClusterScannerTest], and what is
 * left is a ratio, two clamps, and the decision to say nothing at all.
 */
class TranscodeSizeProjectorTest {
    private var mediaMillis: Long? = null
    private val scanner =
        mockk<MkvClusterScanner> {
            every { mediaMillisReceived } answers { mediaMillis }
        }

    @Test
    fun `there is no projection before the first cluster`() {
        // The row keeps saying "up to X" until there is something better to say.
        projector().project(bytesReceived = 10_000_000L).shouldBeNull()
    }

    @Test
    fun `there is no projection before the first byte`() {
        mediaMillis = 5_000L

        projector().project(bytesReceived = 0L).shouldBeNull()
    }

    @Test
    fun `a zero media clock is treated as no evidence rather than divided by`() {
        mediaMillis = 0L

        projector().project(bytesReceived = 10_000_000L).shouldBeNull()
    }

    @Test
    fun `the projection is the observed bitrate extended over the whole runtime`() {
        // 30 s of media in 30 MB, over a one-hour item: 1 MB/s × 3 600 s.
        mediaMillis = 30_000L

        val projected =
            projector(runtimeMillis = HOUR_MILLIS, ceiling = 10_000_000_000L)
                .project(bytesReceived = 30_000_000L)

        projected shouldBe 3_600_000_000L
    }

    @Test
    fun `the projection converges on the true size as more of the file arrives`() {
        // A file that really weighs 500 MB over an hour, measured after a burst of container headers
        // has already skewed the opening seconds.
        val trueBytes = 500_000_000L
        val ceiling = 2_000_000_000L

        mediaMillis = 5_000L
        val early = projector(ceiling = ceiling).project(bytesReceived = 5_000_000L)!!

        mediaMillis = 1_800_000L
        val late = projector(ceiling = ceiling).project(bytesReceived = trueBytes / 2)!!

        // Early: 5 MB per 5 s → 1 MB/s → 3.6 GB, pinned at the ceiling. Late: the real rate.
        early shouldBe ceiling
        late shouldBe trueBytes
        late shouldBeLessThan early
    }

    @Test
    fun `the projection never exceeds the ceiling the enqueue step promised`() {
        // A wildly generous opening ratio — all container, almost no media — must not raise the
        // number the user was already shown.
        mediaMillis = 200L
        val ceiling = 552_000_000L

        projector(ceiling = ceiling).project(bytesReceived = 4_000_000L) shouldBe ceiling
    }

    @Test
    fun `the projection is never below the bytes already on disk`() {
        // An encoder that front-loaded the file: more media time than bytes would justify.
        mediaMillis = HOUR_MILLIS
        val received = 900_000_000L

        projector(ceiling = 2_000_000_000L).project(bytesReceived = received) shouldBe received
    }

    @Test
    fun `bytes past a ceiling that was too small still project to the bytes themselves`() {
        // The lower clamp wins over the upper one: the file cannot be smaller than what has landed.
        // `ItemProgress.bytesTotal` grows the same way.
        mediaMillis = HOUR_MILLIS
        val received = 700_000_000L

        projector(ceiling = 100_000_000L).project(bytesReceived = received) shouldBe received
    }

    @Test
    fun `a longer item projects a larger file from the same measured bitrate`() {
        mediaMillis = 60_000L
        val bytes = 60_000_000L
        val ceiling = 100_000_000_000L

        val hour = projector(runtimeMillis = HOUR_MILLIS, ceiling = ceiling).project(bytes)!!
        val twoHours = projector(runtimeMillis = 2 * HOUR_MILLIS, ceiling = ceiling).project(bytes)!!

        twoHours shouldBe 2 * hour
        twoHours shouldBeGreaterThan hour
    }

    @Test
    fun `bytes handed in are passed straight to the scanner`() {
        val consumed = mutableListOf<Int>()
        val recording =
            mockk<MkvClusterScanner> {
                every { mediaMillisReceived } returns null
                every { consume(any(), any(), any()) } answers { consumed += thirdArg<Int>() }
            }

        TranscodeSizeProjector(HOUR_MILLIS, 1L, recording).consume(ByteArray(64), 0, 64)

        consumed shouldBe listOf(64)
    }

    private fun projector(
        runtimeMillis: Long = HOUR_MILLIS,
        ceiling: Long = 1_000_000_000L,
    ) = TranscodeSizeProjector(runtimeMillis = runtimeMillis, ceilingBytes = ceiling, scanner = scanner)

    private companion object {
        const val HOUR_MILLIS = 3_600_000L
    }
}
