package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [SiblingSeeder] — what finished episodes of a show say the next one will weigh.
 *
 * Two questions, and the second is the one the shipped feature was missing: the size for *one* item
 * (what `DownloadEnqueuer` asks, and what `DownloadQueue` asks again when it starts a row), and the
 * pass over every row still waiting once a sibling lands. A season enqueued in one tap has no
 * finished sibling at enqueue time, so without the second question every episode after the first
 * keeps its "up to X" wording for the whole download.
 */
class SiblingSeederTest {
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        coEvery { downloadDao.completedSiblings(any(), any(), any()) } returns emptyList()
        coEvery { downloadDao.unseededSiblings(any(), any()) } returns emptyList()
        coEvery { itemDao.getItems(any()) } returns emptyList()
    }

    // ---- one item -------------------------------------------------------------------------------

    @Test
    fun `an item is sized from the median rate of its finished siblings`() =
        runTest {
            // 100, 200 and 900 MB per hour: the median is 200, where the mean would be 400.
            givenFinished(uuid(31) to 100_000_000L, uuid(32) to 200_000_000L, uuid(33) to 900_000_000L)

            seeder().seedFor(uuid(2), "Westworld", DownloadQuality.LOW, HOUR_MILLIS, CEILING) shouldBe 200_000_000L
        }

    @Test
    fun `the rate is scaled by this item's own runtime`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)

            seeder().seedFor(uuid(2), "Westworld", DownloadQuality.LOW, HOUR_MILLIS / 2, CEILING) shouldBe
                100_000_000L
        }

    @Test
    fun `the seed can never exceed the ceiling the row was enqueued with`() =
        runTest {
            givenFinished(uuid(31) to 90_000_000_000L)

            seeder().seedFor(uuid(2), "Westworld", DownloadQuality.LOW, HOUR_MILLIS, CEILING) shouldBe CEILING
        }

    @Test
    fun `an item is never evidence for itself`() =
        runTest {
            // A re-enqueued row still carries the bytes of the attempt that failed; extrapolating
            // from those would seed the item with a fraction of its own unfinished size.
            givenFinished(uuid(2) to 100_000_000L)

            seeder().seedFor(uuid(2), "Westworld", DownloadQuality.LOW, HOUR_MILLIS, CEILING).shouldBeNull()
        }

    @Test
    fun `a film has no siblings and is never asked about`() =
        runTest {
            seeder().seedFor(uuid(1), seriesName = null, DownloadQuality.LOW, HOUR_MILLIS, CEILING).shouldBeNull()

            coVerify(exactly = 0) { downloadDao.completedSiblings(any(), any(), any()) }
        }

    @Test
    fun `an original download is never seeded, because its size is already exact`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)

            seeder()
                .seedFor(uuid(2), "Westworld", DownloadQuality.ORIGINAL, HOUR_MILLIS, CEILING)
                .shouldBeNull()

            coVerify(exactly = 0) { downloadDao.completedSiblings(any(), any(), any()) }
        }

    @Test
    fun `a sibling whose runtime is not cached is skipped rather than guessed at`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)
            coEvery { itemDao.getItems(any()) } returns emptyList()

            seeder().seedFor(uuid(2), "Westworld", DownloadQuality.LOW, HOUR_MILLIS, CEILING).shouldBeNull()
        }

    @Test
    fun `a row with no ceiling has nothing to clamp a seed to`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)

            seeder().seedFor(uuid(2), "Westworld", DownloadQuality.LOW, HOUR_MILLIS, ceilingBytes = 0L).shouldBeNull()
        }

    // ---- the pass over waiting rows ---------------------------------------------------------------

    @Test
    fun `a finished episode seeds every waiting row of the same show`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)
            givenWaiting(uuid(2), uuid(3))

            seeder().seedPendingSiblingsOf(finishedEpisode())

            coVerify { downloadDao.setProjectedBytesIfAbsent(uuid(2), 200_000_000L, NOW) }
            coVerify { downloadDao.setProjectedBytesIfAbsent(uuid(3), 200_000_000L, NOW) }
        }

    @Test
    fun `each waiting row is scaled by its own runtime`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)
            givenWaiting(uuid(2))
            // A double-length episode of the same show at the same measured bitrate.
            coEvery { itemDao.getItems(listOf(uuid(2))) } returns listOf(cachedEpisode(uuid(2), HOUR_TICKS * 2))

            seeder().seedPendingSiblingsOf(finishedEpisode())

            coVerify { downloadDao.setProjectedBytesIfAbsent(uuid(2), 400_000_000L, NOW) }
        }

    @Test
    fun `a waiting row's seed is clamped to that row's own ceiling`() =
        runTest {
            givenFinished(uuid(31) to 90_000_000_000L)
            givenWaiting(uuid(2))

            seeder().seedPendingSiblingsOf(finishedEpisode())

            coVerify { downloadDao.setProjectedBytesIfAbsent(uuid(2), CEILING, NOW) }
        }

    @Test
    fun `only rows of the same show at the same quality are asked for`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)
            givenWaiting(uuid(2))

            seeder().seedPendingSiblingsOf(finishedEpisode())

            // The filtering is the query's — another show's rows, another quality's rows, the
            // finished and the failed, and anything that already has a projection never come back.
            coVerify { downloadDao.unseededSiblings("Westworld", DownloadQuality.LOW) }
        }

    @Test
    fun `a waiting row whose runtime is not cached is left on its ceiling`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)
            coEvery { downloadDao.unseededSiblings(any(), any()) } returns
                listOf(download(itemId = uuid(2), bytesTotal = CEILING, quality = DownloadQuality.LOW))
            coEvery { itemDao.getItems(listOf(uuid(2))) } returns emptyList()

            seeder().seedPendingSiblingsOf(finishedEpisode())

            coVerify(exactly = 0) { downloadDao.setProjectedBytesIfAbsent(any(), any(), any()) }
        }

    @Test
    fun `a waiting row with no ceiling is left alone`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)
            coEvery { downloadDao.unseededSiblings(any(), any()) } returns
                listOf(download(itemId = uuid(2), bytesTotal = 0L, quality = DownloadQuality.LOW))
            coEvery { itemDao.getItems(listOf(uuid(2))) } returns listOf(cachedEpisode(uuid(2), HOUR_TICKS))

            seeder().seedPendingSiblingsOf(finishedEpisode())

            coVerify(exactly = 0) { downloadDao.setProjectedBytesIfAbsent(any(), any(), any()) }
        }

    @Test
    fun `nothing is written when there is nothing waiting`() =
        runTest {
            givenFinished(uuid(31) to 200_000_000L)

            seeder().seedPendingSiblingsOf(finishedEpisode())

            // Not even the evidence is read: no waiting row means no question to answer.
            coVerify(exactly = 0) { downloadDao.completedSiblings(any(), any(), any()) }
            coVerify(exactly = 0) { downloadDao.setProjectedBytesIfAbsent(any(), any(), any()) }
        }

    @Test
    fun `a finished film asks nothing, since nothing is waiting on it`() =
        runTest {
            seeder().seedPendingSiblingsOf(download(itemId = uuid(1), quality = DownloadQuality.LOW))

            coVerify(exactly = 0) { downloadDao.unseededSiblings(any(), any()) }
        }

    @Test
    fun `a finished original download seeds nothing`() =
        runTest {
            // Its own size says nothing about what a transcode of the next episode will weigh, and
            // the rows waiting at another quality are not its business either.
            seeder().seedPendingSiblingsOf(
                download(itemId = uuid(31), quality = DownloadQuality.ORIGINAL, seriesName = "Westworld"),
            )

            coVerify(exactly = 0) { downloadDao.unseededSiblings(any(), any()) }
        }

    @Test
    fun `waiting rows keep their ceiling when no sibling can be turned into a rate`() =
        runTest {
            givenWaiting(uuid(2))

            seeder().seedPendingSiblingsOf(finishedEpisode())

            coVerify(exactly = 0) { downloadDao.setProjectedBytesIfAbsent(any(), any(), any()) }
        }

    // ---- helpers --------------------------------------------------------------------------------

    /** Finished *Westworld* rows at `LOW`, each an hour long and each having landed at its size. */
    private fun givenFinished(vararg landed: Pair<UUID, Long>) {
        coEvery { downloadDao.completedSiblings("Westworld", DownloadQuality.LOW, any()) } returns
            landed.map { (id, bytes) ->
                download(
                    itemId = id,
                    status = DownloadStatus.DOWNLOADED,
                    bytesDownloaded = bytes,
                    quality = DownloadQuality.LOW,
                    seriesName = "Westworld",
                )
            }
        coEvery { itemDao.getItems(landed.map { it.first }) } returns
            landed.map { (id, _) -> cachedEpisode(id, HOUR_TICKS) }
    }

    /** Hour-long *Westworld* rows still waiting at `LOW`, each with the same ceiling. */
    private fun givenWaiting(vararg ids: UUID) {
        coEvery { downloadDao.unseededSiblings("Westworld", DownloadQuality.LOW) } returns
            ids.map { id ->
                download(
                    itemId = id,
                    status = DownloadStatus.QUEUED,
                    bytesTotal = CEILING,
                    quality = DownloadQuality.LOW,
                    seriesName = "Westworld",
                )
            }
        coEvery { itemDao.getItems(ids.toList()) } returns ids.map { cachedEpisode(it, HOUR_TICKS) }
    }

    private fun finishedEpisode() =
        download(
            itemId = uuid(31),
            status = DownloadStatus.DOWNLOADED,
            quality = DownloadQuality.LOW,
            seriesName = "Westworld",
        )

    private fun cachedEpisode(
        id: UUID,
        runTimeTicks: Long,
    ) = ItemEntity(
        id = id,
        name = "Sibling",
        sortName = "Sibling",
        type = ItemType.EPISODE,
        source = ItemSource.DOWNLOAD,
        cachedAt = NOW,
        runTimeTicks = runTimeTicks,
        dto = "{}",
    )

    private fun seeder() = SiblingSeeder(downloadDao = downloadDao, itemDao = itemDao, clock = clock)

    private companion object {
        /** One hour in `runTimeTicks` (100 ns each), and the same hour in milliseconds. */
        const val HOUR_TICKS = 36_000_000_000L
        const val HOUR_MILLIS = 3_600_000L

        /** The enqueue-time upper bound every seed is clamped by. */
        const val CEILING = 500_000_000L
    }
}
