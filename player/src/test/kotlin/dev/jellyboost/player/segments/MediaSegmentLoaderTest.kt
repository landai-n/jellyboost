package dev.jellyboost.player.segments

import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.PlayerApi
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

/**
 * Everything here is about *not* being loud. The Media Segments API needs a Jellyfin 10.10+ server
 * with a detection plugin, so the common answer is a 404 or an empty list, and the plan makes the
 * feature server-only — none of which is a failure the user should ever see.
 */
class MediaSegmentLoaderTest {
    private val api = mockk<PlayerApi>()
    private val loader = MediaSegmentLoader(api)

    @Test
    fun `converts the server's ticks into the milliseconds the player compares against`() =
        runTest {
            coEvery { api.getMediaSegments(any(), any()) } returns
                listOf(segment(MediaSegmentType.INTRO, startTicks = 300_000_000L, endTicks = 1_200_000_000L))

            val segments = loader.load(PlayerFixtures.remoteSource())

            segments shouldContainExactly
                listOf(MediaSegment(kind = MediaSegmentKind.INTRO, startMs = 30_000L, endMs = 120_000L))
        }

    @Test
    fun `asks only for the types the app can act on`() =
        runTest {
            coEvery { api.getMediaSegments(any(), any()) } returns emptyList()

            loader.load(PlayerFixtures.remoteSource())

            coVerify {
                api.getMediaSegments(
                    PlayerFixtures.ITEM_ID,
                    listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO),
                )
            }
        }

    @Test
    fun `returns the segments in playback order`() =
        runTest {
            coEvery { api.getMediaSegments(any(), any()) } returns
                listOf(
                    segment(MediaSegmentType.OUTRO, 60_000_000_000L, 72_000_000_000L),
                    segment(MediaSegmentType.INTRO, 0L, 900_000_000L),
                )

            loader.load(PlayerFixtures.remoteSource()).map { it.kind } shouldContainExactly
                listOf(MediaSegmentKind.INTRO, MediaSegmentKind.OUTRO)
        }

    @Test
    fun `drops a type the app has no behaviour for`() =
        runTest {
            coEvery { api.getMediaSegments(any(), any()) } returns
                listOf(
                    segment(MediaSegmentType.COMMERCIAL, 0L, 900_000_000L),
                    segment(MediaSegmentType.INTRO, 900_000_000L, 1_800_000_000L),
                )

            loader.load(PlayerFixtures.remoteSource()) shouldHaveSize 1
        }

    @Test
    fun `drops a segment that ends before it starts`() =
        runTest {
            coEvery { api.getMediaSegments(any(), any()) } returns
                listOf(segment(MediaSegmentType.INTRO, 900_000_000L, 900_000_000L))

            loader.load(PlayerFixtures.remoteSource()).shouldBeEmpty()
        }

    @Test
    fun `a server without the segments endpoint simply has no segments`() =
        runTest {
            coEvery { api.getMediaSegments(any(), any()) } throws IOException("404")

            loader.load(PlayerFixtures.remoteSource()).shouldBeEmpty()
        }

    @Test
    fun `a downloaded item is never asked about`() =
        runTest {
            // No stubbing at all: the feature is server-only, so touching the api would throw.
            loader.load(PlayerFixtures.localSource()).shouldBeEmpty()
        }

    @Test
    fun `a segment's duration is what a skip would jump`() {
        val segment = MediaSegment(MediaSegmentKind.INTRO, startMs = 30_000L, endMs = 120_000L)

        segment.durationMs shouldBe 90_000L
        segment.contains(30_000L) shouldBe true
        segment.contains(119_999L) shouldBe true
        // Exclusive at the end: the millisecond a skip lands on is already outside.
        segment.contains(120_000L) shouldBe false
    }

    private fun segment(
        type: MediaSegmentType,
        startTicks: Long,
        endTicks: Long,
    ): MediaSegmentDto =
        MediaSegmentDto(
            id = UUID.randomUUID(),
            itemId = PlayerFixtures.ITEM_ID,
            type = type,
            startTicks = startTicks,
            endTicks = endTicks,
        )
}
