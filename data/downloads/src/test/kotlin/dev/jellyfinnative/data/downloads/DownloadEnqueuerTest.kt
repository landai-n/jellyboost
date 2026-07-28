package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.episode
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [DownloadEnqueuer] — docs/PLAN.md's "Enqueue" step.
 *
 * The rule the tests exist for is step 3: the item **and its parents** are cached with
 * `source = DOWNLOAD`. Get that wrong and the download works perfectly while the offline library
 * shows an episode that cannot reach its own series page.
 */
class DownloadEnqueuerTest {
    private val api = mockk<DownloadApi>()
    private val itemDao = mockk<ItemDao>(relaxUnitFun = true)
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val mapper = mockk<ItemEntityMapper>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private val upserted = slot<List<ItemEntity>>()
    private val row = slot<DownloadEntity>()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { downloadDao.upsert(capture(row)) } just Runs
        coEvery { downloadDao.get(any()) } returns null
        coEvery { downloadDao.maxQueuePosition() } returns null
        // `toEntity` is overloaded (items and library views), so the argument types are explicit.
        every { mapper.toEntity(any<BaseItemDto>(), any<ItemSource>(), any<Instant>()) } answers {
            entity(firstArg(), secondArg())
        }
    }

    // ---- the cache write ------------------------------------------------------------------------

    @Test
    fun `the item is cached as a download, never as browse cache`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(1))) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            // A BROWSE_CACHE row is evictable, and evicting it would orphan the files on disk.
            upserted.captured.map { it.source }.distinct() shouldContainExactlyInAnyOrder listOf(ItemSource.DOWNLOAD)
        }

    @Test
    fun `an episode caches its series and season alongside itself`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
                AppResult.Success(listOf(movie(id = uuid(10)), movie(id = uuid(11))))

            enqueuer().enqueue(uuid(2), USER)

            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(2), uuid(10), uuid(11))
        }

    @Test
    fun `a movie fetches no parents at all`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(1))) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            coVerify(exactly = 1) { api.getFullItems(any()) }
        }

    @Test
    fun `a failing parent fetch still enqueues the download`() =
        runTest {
            // Losing the series page offline is a degradation; losing the download is a failure.
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
                AppResult.Failure(AppError.Network())

            enqueuer().enqueue(uuid(2), USER).shouldBeInstanceOf<AppResult.Success<DownloadEntity>>()
        }

    // ---- the download row -----------------------------------------------------------------------

    @Test
    fun `a new download starts QUEUED at the end of the queue`() =
        runTest {
            coEvery { downloadDao.maxQueuePosition() } returns 4
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.captured.status shouldBe DownloadStatus.QUEUED
            row.captured.queuePosition shouldBe 5
        }

    @Test
    fun `the row carries the expected size so the queue can show a percentage immediately`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.captured.bytesTotal shouldBe 2_100_000_000L
            row.captured.mediaSourceId shouldBe "source-1"
        }

    @Test
    fun `the row carries the directory the files will land in`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns AppResult.Success(emptyList())

            enqueuer().enqueue(uuid(2), USER)

            // Denormalised on purpose: the delete cascade needs it after the item row is gone.
            row.captured.directoryName shouldBe "Westworld - S01E02 - Chestnut"
            row.captured.seriesName shouldBe "Westworld"
        }

    @Test
    fun `re-enqueueing an item keeps its place in the queue and its bytes`() =
        runTest {
            val existing =
                DownloadFixtures.download(
                    status = DownloadStatus.ERROR,
                    queuePosition = 2,
                    bytesDownloaded = 900_000L,
                )
            coEvery { downloadDao.get(uuid(1)) } returns existing
            coEvery { downloadDao.maxQueuePosition() } returns 9
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            // Retrying a failure must not send the item to the back of the queue, nor throw away
            // the bytes already on disk that the Range resume will pick up from.
            row.captured.queuePosition shouldBe 2
            row.captured.bytesDownloaded shouldBe 900_000L
            row.captured.status shouldBe DownloadStatus.QUEUED
            row.captured.errorMessage shouldBe null
        }

    @Test
    fun `the created timestamp survives a re-enqueue`() =
        runTest {
            val earlier = Instant.parse("2026-07-01T08:00:00Z")
            coEvery { downloadDao.get(uuid(1)) } returns DownloadFixtures.download().copy(createdAt = earlier)
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.captured.createdAt shouldBe earlier
            row.captured.updatedAt shouldBe NOW
        }

    // ---- failures -------------------------------------------------------------------------------

    @Test
    fun `a failed re-fetch writes nothing`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Failure(AppError.Network())

            enqueuer().enqueue(uuid(1), USER).shouldBeInstanceOf<AppResult.Failure>()

            coVerify(exactly = 0) { itemDao.upsert(any()) }
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    @Test
    fun `an item the server no longer knows is a NotFound, not a crash`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(emptyList())

            val result = enqueuer().enqueue(uuid(1), USER)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
        }

    @Test
    fun `a failing cache write fails the enqueue rather than queueing an invisible download`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))
            coEvery { itemDao.upsert(any()) } throws IllegalStateException("disk full")

            val result = enqueuer().enqueue(uuid(1), USER)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Storage>()
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    // ---- helpers --------------------------------------------------------------------------------

    private fun enqueuer() =
        DownloadEnqueuer(
            api = api,
            itemDao = itemDao,
            downloadDao = downloadDao,
            mapper = mapper,
            clock = clock,
        )

    private fun entity(
        dto: BaseItemDto,
        source: ItemSource,
    ) = ItemEntity(
        id = dto.id,
        name = dto.name.orEmpty(),
        sortName = dto.name.orEmpty(),
        type = ItemType.MOVIE,
        source = source,
        cachedAt = NOW,
        dto = "{}",
    )

    private companion object {
        val USER = uuid(99)
    }
}
