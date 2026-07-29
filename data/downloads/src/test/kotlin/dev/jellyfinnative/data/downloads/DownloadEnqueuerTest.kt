package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.episode
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.season
import dev.jellyfinnative.data.downloads.DownloadFixtures.series
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
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
 *
 * The second half of the file pins **container expansion** (DECISIONS.md, 2026-07-29): a season or
 * a series is a folder, and enqueueing one has to become one download per episode rather than a row
 * for the folder itself — which is the row that produced *"The server couldn't send this download
 * (error 400)"*.
 */
class DownloadEnqueuerTest {
    private val api = mockk<DownloadApi>()
    private val itemDao = mockk<ItemDao>(relaxUnitFun = true)
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val deleter = mockk<DownloadDeleter>()
    private val mapper = mockk<ItemEntityMapper>()
    private val downloadQuality = MutableStateFlow(DownloadQuality.ORIGINAL)
    private val appPreferences =
        mockk<AppPreferences> {
            every { this@mockk.downloadQuality } returns this@DownloadEnqueuerTest.downloadQuality
        }
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private val upserted = slot<List<ItemEntity>>()
    private val rows = mutableListOf<DownloadEntity>()

    /** The single row a non-container enqueue writes — most tests only ever create one. */
    private val row: DownloadEntity get() = rows.single()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { downloadDao.upsert(capture(rows)) } just Runs
        coEvery { downloadDao.get(any()) } returns null
        coEvery { downloadDao.maxQueuePosition() } returns null
        // No finished siblings and no cached runtimes by default: seeding is opt-in per test.
        coEvery { downloadDao.completedSiblings(any(), any(), any()) } returns emptyList()
        coEvery { itemDao.getItems(any()) } returns emptyList()
        coEvery { deleter.delete(any()) } returns 0L
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

            enqueuer().enqueue(uuid(2), USER).shouldBeInstanceOf<AppResult.Success<List<DownloadEntity>>>()
        }

    // ---- the download row -----------------------------------------------------------------------

    @Test
    fun `a new download starts QUEUED at the end of the queue`() =
        runTest {
            coEvery { downloadDao.maxQueuePosition() } returns 4
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.status shouldBe DownloadStatus.QUEUED
            row.queuePosition shouldBe 5
        }

    @Test
    fun `the row carries the expected size so the queue can show a percentage immediately`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.bytesTotal shouldBe 2_100_000_000L
            row.mediaSourceId shouldBe "source-1"
        }

    @Test
    fun `the row carries the directory the files will land in`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns AppResult.Success(emptyList())

            enqueuer().enqueue(uuid(2), USER)

            // Denormalised on purpose: the delete cascade needs it after the item row is gone.
            row.directoryName shouldBe "Westworld - S01E02 - Chestnut"
            row.seriesName shouldBe "Westworld"
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
            row.queuePosition shouldBe 2
            row.bytesDownloaded shouldBe 900_000L
            row.status shouldBe DownloadStatus.QUEUED
            row.errorMessage shouldBe null
        }

    @Test
    fun `the created timestamp survives a re-enqueue`() =
        runTest {
            val earlier = Instant.parse("2026-07-01T08:00:00Z")
            coEvery { downloadDao.get(uuid(1)) } returns DownloadFixtures.download().copy(createdAt = earlier)
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.createdAt shouldBe earlier
            row.updatedAt shouldBe NOW
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

    // ---- download quality (M9) ------------------------------------------------------------------

    @Test
    fun `the preference in force when the user taps Download is stamped on the row`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            // Stored, not re-read later: the queue plans every run from this column, so a user who
            // changes the setting mid-transfer cannot make the pipeline append incompatible bytes.
            row.quality shouldBe DownloadQuality.MEDIUM
        }

    @Test
    fun `an original download keeps the exact size the server reported`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(sizeBytes = 2_100_000_000L, runTimeTicks = HOUR_TICKS)))

            enqueuer().enqueue(uuid(1), USER)

            row.bytesTotal shouldBe 2_100_000_000L
        }

    @Test
    fun `a transcoded download is sized from its runtime and bitrate instead`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sizeBytes = 2_100_000_000L,
                            // Above the MEDIUM cap, so the cap — not the source — bounds the estimate.
                            sourceBitRate = DownloadQuality.MEDIUM.totalBitRate!! * 2,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // The server will not send a Content-Length for a file it has not encoded yet, so an
            // hour at 8 Mbps + 192 kbps of audio is the only number the queue tab can show.
            val expected = 3_600L * (DownloadQuality.MEDIUM.videoBitRate!! + DownloadQuality.AUDIO_BITRATE) / 8
            row.bytesTotal shouldBe expected
        }

    @Test
    fun `a transcoded download of a source under the cap is sized from the source bitrate`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            // Below the LOW cap (3 Mbps + 192 kbps audio) — an HEVC source, say — so the transcode
            // cannot need more bits per second than the source already uses.
            val sourceBitRate = 1_500_000
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sizeBytes = 2_100_000_000L,
                            sourceBitRate = sourceBitRate,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            val expected = 3_600L * sourceBitRate / 8
            row.bytesTotal shouldBe expected
        }

    @Test
    fun `a transcoded download with no source bitrate falls back to the quality cap`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sizeBytes = 2_100_000_000L,
                            sourceBitRate = null,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            val expected = 3_600L * (DownloadQuality.LOW.videoBitRate!! + DownloadQuality.AUDIO_BITRATE) / 8
            row.bytesTotal shouldBe expected
        }

    @Test
    fun `a transcoded download of an item with no runtime falls back to an unknown size`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(sizeBytes = 2_100_000_000L, runTimeTicks = null)))

            enqueuer().enqueue(uuid(1), USER)

            // Zero is the pipeline's "unknown", which renders as an indeterminate bar. Reporting the
            // *source* size here would promise a file the user is not going to get.
            row.bytesTotal shouldBe 0L
        }

    // ---- containers expand into episodes (docs/POLISH.md: "downloading a season fails") ----------

    @Test
    fun `a season becomes one download per episode, in the order the server lists them`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2), uuid(3)))
            // Deliberately answered out of order: `getItems(ids = …)` sorts to its own taste, and
            // the queue's order is the one that was asked for.
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(3), episodeNumber = 3), episode(id = uuid(2))))

            val result = enqueuer().enqueue(uuid(11), USER)

            // The season itself is never a download row: `/Items/{id}/Download` answers 400 for a
            // folder, which is the bug this whole expansion exists to fix.
            rows.map { it.itemId } shouldContainExactly listOf(uuid(2), uuid(3))
            rows.map { it.queuePosition } shouldContainExactly listOf(1, 2)
            result.shouldBeInstanceOf<AppResult.Success<List<DownloadEntity>>>().value.size shouldBe 2
        }

    @Test
    fun `a series is expanded across every one of its seasons at once`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(10))) } returns AppResult.Success(listOf(series()))
            // No season id: one request for the whole show, in broadcast order.
            coEvery { api.getEpisodeIds(uuid(10), null) } returns AppResult.Success(listOf(uuid(2), uuid(3)))
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(2)), episode(id = uuid(3), episodeNumber = 3)))
            coEvery { api.getFullItems(listOf(uuid(11))) } returns AppResult.Success(listOf(season()))

            enqueuer().enqueue(uuid(10), USER)

            coVerify(exactly = 1) { api.getEpisodeIds(uuid(10), null) }
            rows.map { it.itemId } shouldContainExactly listOf(uuid(2), uuid(3))
        }

    @Test
    fun `episodes already spoken for are left exactly as they are`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2), uuid(3)))
            coEvery { downloadDao.get(uuid(2)) } returns
                DownloadFixtures.download(itemId = uuid(2), status = DownloadStatus.DOWNLOADED)
            coEvery { api.getFullItems(listOf(uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(3), episodeNumber = 3)))

            enqueuer().enqueue(uuid(11), USER)

            // Re-tapping Download on a half-downloaded season must not restart what is already
            // there — and must not re-fetch it either.
            rows.map { it.itemId } shouldContainExactly listOf(uuid(3))
            coVerify(exactly = 0) { api.getFullItems(listOf(uuid(2), uuid(3))) }
        }

    @Test
    fun `a failed episode is the one thing a second tap does re-enqueue`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { downloadDao.get(uuid(2)) } returns
                DownloadFixtures.download(
                    itemId = uuid(2),
                    status = DownloadStatus.ERROR,
                    queuePosition = 4,
                    bytesDownloaded = 900_000L,
                )
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            row.status shouldBe DownloadStatus.QUEUED
            // Retrying keeps the place in the queue and the bytes the Range resume picks up from.
            row.queuePosition shouldBe 4
            row.bytesDownloaded shouldBe 900_000L
        }

    @Test
    fun `the season's own unusable download row is cleaned up as the episodes are queued`() =
        runTest {
            // The rows the user is stuck with: a season enqueued as if it were a file, permanently
            // ERROR because no retry of `/Items/{seasonId}/Download` can ever succeed.
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { downloadDao.get(uuid(11)) } returns
                DownloadFixtures.download(itemId = uuid(11), status = DownloadStatus.ERROR)
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            coVerify(exactly = 1) { deleter.delete(uuid(11)) }
            rows.map { it.itemId } shouldContainExactly listOf(uuid(2))
        }

    @Test
    fun `a container with no row of its own has nothing to clean up`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            coVerify(exactly = 0) { deleter.delete(any()) }
        }

    @Test
    fun `every episode of a season is stamped with the one quality in force at the tap`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            givenSeason(episodeIds = listOf(uuid(2), uuid(3)))
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(2)), episode(id = uuid(3), episodeNumber = 3)))

            enqueuer().enqueue(uuid(11), USER)

            // One tap, one quality: a preference changed while the season drains cannot make half
            // the episodes a different file (DECISIONS.md, 2026-07-29).
            rows.map { it.quality }.distinct() shouldContainExactly listOf(DownloadQuality.MEDIUM)
        }

    @Test
    fun `expanding a season caches the season, its series and every episode`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            // Without the season and series rows the downloaded episodes cannot be navigated to
            // offline — the same rule a single episode download follows.
            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(11), uuid(2), uuid(10))
        }

    @Test
    fun `a season whose every episode is already downloaded queues nothing and reports success`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { downloadDao.get(uuid(2)) } returns
                DownloadFixtures.download(itemId = uuid(2), status = DownloadStatus.DOWNLOADED)

            val result = enqueuer().enqueue(uuid(11), USER)

            result.shouldBeInstanceOf<AppResult.Success<List<DownloadEntity>>>().value.shouldBeEmpty()
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    @Test
    fun `a season the server lists no episodes for fails instead of queueing the season`() =
        runTest {
            givenSeason(episodeIds = emptyList())

            val result = enqueuer().enqueue(uuid(11), USER)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    @Test
    fun `a folder this pipeline cannot expand is refused, never queued`() =
        runTest {
            // A box set has no episodes endpoint and no file of its own; queueing it would recreate
            // exactly the 400 this fix removes.
            val boxSet =
                BaseItemDto(id = uuid(20), type = BaseItemKind.BOX_SET, name = "Trilogy", isFolder = true)
            coEvery { api.getFullItems(listOf(uuid(20))) } returns AppResult.Success(listOf(boxSet))

            enqueuer().enqueue(uuid(20), USER).shouldBeInstanceOf<AppResult.Failure>()

            coVerify(exactly = 0) { downloadDao.upsert(any()) }
            coVerify(exactly = 0) { api.getEpisodeIds(any(), any()) }
        }

    @Test
    fun `a failing episode listing writes nothing`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(11))) } returns AppResult.Success(listOf(season()))
            coEvery { api.getEpisodeIds(uuid(10), uuid(11)) } returns AppResult.Failure(AppError.Network())

            enqueuer().enqueue(uuid(11), USER).shouldBeInstanceOf<AppResult.Failure>()

            coVerify(exactly = 0) { itemDao.upsert(any()) }
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    // ---- helpers --------------------------------------------------------------------------------

    /** A season (`uuid(11)`) of a series (`uuid(10)`) the server lists [episodeIds] under. */
    private fun givenSeason(episodeIds: List<java.util.UUID>) {
        coEvery { api.getFullItems(listOf(uuid(11))) } returns AppResult.Success(listOf(season()))
        coEvery { api.getEpisodeIds(uuid(10), uuid(11)) } returns AppResult.Success(episodeIds)
        // The episodes' parents: the season is already cached, so only the series is fetched.
        coEvery { api.getFullItems(listOf(uuid(10))) } returns AppResult.Success(listOf(series()))
    }

    private fun enqueuer() =
        DownloadEnqueuer(
            api = api,
            itemDao = itemDao,
            downloadDao = downloadDao,
            deleter = deleter,
            mapper = mapper,
            appPreferences = appPreferences,
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

        /** One hour in `runTimeTicks` (100 ns each). */
        const val HOUR_TICKS = 36_000_000_000L
    }
}
