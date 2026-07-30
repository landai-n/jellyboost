package dev.jellyfinnative.data.downloads.impl

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.session.SessionGate
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadApi
import dev.jellyfinnative.data.downloads.DownloadFixtures
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.episode
import dev.jellyfinnative.data.downloads.DownloadFixtures.season
import dev.jellyfinnative.data.downloads.DownloadFixtures.series
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import dev.jellyfinnative.data.downloads.engine.DownloadQueue
import dev.jellyfinnative.data.downloads.engine.DownloadQueueListener
import dev.jellyfinnative.data.downloads.engine.FileDownloader
import dev.jellyfinnative.data.downloads.engine.OrphanSweeper
import dev.jellyfinnative.data.downloads.engine.SiblingSeeder
import dev.jellyfinnative.data.downloads.plan.DownloadFilePlanner
import dev.jellyfinnative.data.downloads.plan.DownloadUrlFactory
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The user's own scenario, end to end: a season of transcoded episodes queued in **one tap**.
 *
 * At enqueue there is nothing finished to learn from, so every row starts on its ceiling ("up to
 * X") — that part is by design and this test states it. What was missing is what happens next: the
 * first episode lands, and the rows still waiting behind it have to pick up a size from it. Before
 * `SiblingSeeder`, no code path ever revisited an enqueued row, so episodes 2..N kept the ceiling
 * wording for the whole of the season however many siblings finished.
 *
 * Room is stood in for by a map rather than mocked call by call: the point of the test is the
 * *sequence* — enqueue, finish one, re-read the others — and a per-call stub could not express it.
 * The fake implements exactly what the two new statements say (`unseededSiblings`'s filters, and
 * `setProjectedBytesIfAbsent`'s refusal to overwrite), so the SQL is what is being modelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonSeedingScenarioTest {
    private val downloads = linkedMapOf<UUID, DownloadEntity>()
    private val items = linkedMapOf<UUID, ItemEntity>()
    private val files = mutableListOf<DownloadFileEntity>()

    /** Every `(itemId, bytes)` a seed was written for, in order. */
    private val seeds = mutableListOf<Pair<UUID, Long>>()

    private val api = mockk<DownloadApi>()
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>(relaxUnitFun = true)
    private val mapper = mockk<ItemEntityMapper>()
    private val deleter = mockk<DownloadDeleter>()
    private val storage = mockk<DownloadStorage>()
    private val sweeper = mockk<OrphanSweeper> { coEvery { sweep() } returns 0L }
    private val downloader = mockk<FileDownloader>()
    private val urls = mockk<DownloadUrlFactory>(relaxed = true)
    private val sessionGate = mockk<SessionGate>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val quality = MutableStateFlow(DownloadQuality.LOW)
    private val appPreferences =
        mockk<AppPreferences> { every { downloadQuality } returns quality }

    private var nextFileId = 1L
    private var runnableLeft = 0

    @BeforeEach
    fun setUp() {
        givenTheServer()
        givenTheDatabase()
        givenTheTransfer()
    }

    @Test
    fun `a season queued in one tap starts with nothing to be seeded from`() =
        runTest {
            enqueuer().enqueue(SEASON, USER)

            // Not a bug, and not fixable at enqueue: the evidence a seed is made of is a *finished*
            // download, and at this instant the season has none. Every row shows "up to X".
            downloads.keys.toList() shouldContainExactly listOf(EPISODE_1, EPISODE_2, EPISODE_3)
            downloads.values.map { it.projectedBytes } shouldContainExactly listOf<Long?>(null, null, null)
            downloads.values.forEach { it.quality shouldBe DownloadQuality.LOW }
            // The column the whole feature keys on: an episode enqueued through season expansion
            // has to carry its series name, or no sibling query can ever match it.
            downloads.values.forEach { it.seriesName shouldBe "Westworld" }
        }

    @Test
    fun `the episode that finishes first seeds the ones still waiting behind it`() =
        runTest {
            enqueuer().enqueue(SEASON, USER)
            runnableLeft = 1

            queue().drain(RecordingListener())

            // The bug the user reported: before the fix nothing ever came back to these rows, so
            // they stayed on "up to 1,4 GB" while episode 1 sat finished at 200 MB next to them.
            val second = downloads.getValue(EPISODE_2)
            val third = downloads.getValue(EPISODE_3)
            second.projectedBytes.shouldNotBeNull()
            third.projectedBytes.shouldNotBeNull()
            // Episode 1 landed at 200 MB of media plus its two images, over an hour; episodes 2 and
            // 3 are the same length, so that is what they are expected to weigh. (Within a byte:
            // the rate is a `Double`, and the round trip through it is not exact.)
            second.projectedBytes!! shouldBeGreaterThanOrEqual LANDED_BYTES - 1
            second.projectedBytes!! shouldBeLessThanOrEqual LANDED_BYTES
            third.projectedBytes shouldBe second.projectedBytes
            // And the ceiling is still a promise: a seed may only ever move the figure down.
            second.projectedBytes!! shouldBeLessThanOrEqual second.bytesTotal
            // `bytesTotal` itself is untouched — the ceiling and the projection are two columns
            // precisely so a guess cannot overwrite a deterministic bound.
            second.bytesTotal shouldBe third.bytesTotal
        }

    @Test
    fun `a row already carrying a projection is not overwritten by a later sibling`() =
        runTest {
            enqueuer().enqueue(SEASON, USER)
            // Episode 2 is mid-transfer with a measurement of its own from the MKV scanner.
            downloads[EPISODE_2] = downloads.getValue(EPISODE_2).copy(projectedBytes = 123_456_789L)
            runnableLeft = 1

            queue().drain(RecordingListener())

            // A measurement outranks a guess made from other episodes.
            downloads.getValue(EPISODE_2).projectedBytes shouldBe 123_456_789L
            seeds.map { it.first } shouldContainExactly listOf(EPISODE_3)
        }

    @Test
    fun `rows of another show and another quality are left alone`() =
        runTest {
            enqueuer().enqueue(SEASON, USER)
            val stranger =
                DownloadFixtures.download(
                    itemId = uuid(90),
                    quality = DownloadQuality.LOW,
                    bytesTotal = 1_000_000_000L,
                    seriesName = "Severance",
                )
            val otherQuality =
                DownloadFixtures.download(
                    itemId = uuid(91),
                    quality = DownloadQuality.HIGH,
                    bytesTotal = 1_000_000_000L,
                    seriesName = "Westworld",
                )
            downloads[stranger.itemId] = stranger
            downloads[otherQuality.itemId] = otherQuality
            items[stranger.itemId] = cached(uuid(90), HOUR_TICKS)
            items[otherQuality.itemId] = cached(uuid(91), HOUR_TICKS)
            runnableLeft = 1

            queue().drain(RecordingListener())

            downloads.getValue(uuid(90)).projectedBytes shouldBe null
            downloads.getValue(uuid(91)).projectedBytes shouldBe null
        }

    @Test
    fun `a finished or failed row is not a row waiting for a size`() =
        runTest {
            enqueuer().enqueue(SEASON, USER)
            downloads[EPISODE_2] = downloads.getValue(EPISODE_2).copy(status = DownloadStatus.ERROR)
            downloads[EPISODE_3] = downloads.getValue(EPISODE_3).copy(status = DownloadStatus.DOWNLOADED)
            runnableLeft = 1

            queue().drain(RecordingListener())

            seeds.shouldBeEmpty()
        }

    // ---- the world --------------------------------------------------------------------------------

    /** A season of three hour-long episodes the server will have to re-encode at `LOW`. */
    private fun givenTheServer() {
        val episodes = EPISODE_IDS.map { id -> episodeDto(id) }
        coEvery { api.getFullItems(listOf(SEASON)) } returns AppResult.Success(listOf(season()))
        coEvery { api.getEpisodeIds(SERIES, SEASON) } returns AppResult.Success(EPISODE_IDS)
        coEvery { api.getFullItems(EPISODE_IDS) } returns AppResult.Success(episodes)
        coEvery { api.getFullItems(listOf(SERIES)) } returns AppResult.Success(listOf(series()))
        coEvery { deleter.delete(any()) } returns 0L
    }

    /** Room, as far as these two collaborators can tell. */
    @Suppress("LongMethod")
    private fun givenTheDatabase() {
        every { mapper.toEntity(any<BaseItemDto>(), any<ItemSource>(), any<Instant>()) } answers {
            val dto = firstArg<BaseItemDto>()
            ItemEntity(
                id = dto.id,
                name = dto.name.orEmpty(),
                sortName = dto.name.orEmpty(),
                type = ItemType.EPISODE,
                source = secondArg<ItemSource>(),
                cachedAt = NOW,
                runTimeTicks = dto.runTimeTicks,
                seriesName = dto.seriesName,
                dto = "{}",
            )
        }
        every { mapper.toDtoOrNull(any()) } answers { episodeDto(firstArg<ItemEntity>().id) }

        coEvery { itemDao.upsert(any()) } answers {
            firstArg<List<ItemEntity>>().forEach { items[it.id] = it }
        }
        coEvery { itemDao.getItem(any()) } answers { items[firstArg()] }
        coEvery { itemDao.getItems(any()) } answers {
            firstArg<List<UUID>>().mapNotNull { items[it] }
        }

        coEvery { downloadDao.upsert(any()) } answers { downloads[firstArg<DownloadEntity>().itemId] = firstArg() }
        coEvery { downloadDao.get(any()) } answers { downloads[firstArg()] }
        coEvery { downloadDao.maxQueuePosition() } answers { downloads.values.maxOfOrNull { it.queuePosition } }
        coEvery { downloadDao.setStatus(any(), any(), any(), any()) } answers {
            downloads.computeIfPresent(firstArg()) { _, row -> row.copy(status = secondArg()) }
        }
        coEvery { downloadDao.updateProgress(any(), any(), any(), any(), any()) } answers {
            downloads.computeIfPresent(firstArg()) { _, row ->
                row.copy(bytesDownloaded = secondArg(), bytesTotal = thirdArg(), projectedBytes = arg(3))
            }
        }
        coEvery { downloadDao.completedSiblings(any(), any(), any()) } answers {
            downloads.values
                .filter {
                    it.seriesName == firstArg<String>() &&
                        it.quality == secondArg<DownloadQuality>() &&
                        it.status == DownloadStatus.DOWNLOADED
                }.take(thirdArg())
        }
        // The two new statements, modelled clause for clause.
        coEvery { downloadDao.unseededSiblings(any(), any()) } answers {
            downloads.values.filter {
                it.seriesName == firstArg<String>() &&
                    it.quality == secondArg<DownloadQuality>() &&
                    it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED) &&
                    it.projectedBytes == null &&
                    !it.sizeIsExact
            }
        }
        coEvery { downloadDao.setProjectedBytesIfAbsent(any(), any(), any()) } answers {
            val itemId = firstArg<UUID>()
            val bytes = secondArg<Long>()
            downloads[itemId]?.takeIf { it.projectedBytes == null }?.let {
                downloads[itemId] = it.copy(projectedBytes = bytes)
                seeds += itemId to bytes
            }
        }

        coEvery { downloadDao.insertFile(any()) } answers {
            files += firstArg<DownloadFileEntity>().copy(id = nextFileId)
            nextFileId++
        }
        // The queue hands out one item per call, and only as many as the test asked for: the
        // scenario is "episode 1 finished", not "the whole season did".
        coEvery { downloadDao.nextRunnable() } answers {
            downloads.values
                .firstOrNull { it.status == DownloadStatus.QUEUED }
                ?.takeIf { runnableLeft-- > 0 }
                ?.let { DownloadWithFiles(download = it, files = emptyList()) }
        }
    }

    /** The transfer itself: 200 MB of transcode, and two small images. */
    private fun givenTheTransfer() {
        every { storage.prepareItemDirectory(any()) } returns File("/tmp/downloads")
        every { storage.resolve(any(), any()) } answers { File("/tmp/downloads/${secondArg<String>()}") }
        every { urls.transcodedVideoUrl(any(), any(), any(), any()) } returns TRANSCODE_URL
        every { urls.imageUrl(any(), any(), any(), any()) } returns IMAGE_URL
        coEvery { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) } returns MEDIA_BYTES
        coEvery { downloader.download(IMAGE_URL, any(), any(), any(), any(), any()) } returns IMAGE_BYTES
        coEvery { sessionGate.ensureSession() } returns true
    }

    private fun episodeDto(id: UUID): BaseItemDto =
        episode(
            id = id,
            name = "Episode ${EPISODE_IDS.indexOf(id) + 1}",
            runTimeTicks = HOUR_TICKS,
            // Far above `LOW`'s cap, so the row gets a genuine ceiling rather than a remux figure.
            sourceBitRate = 40_000_000,
            // The file that bitrate implies over an hour — 18 GB against a 1,4 GB transcode, so the
            // row stays `LOW` instead of falling back to the original (`DownloadEnqueuer.planQuality`).
            sizeBytes = 3_600L * 40_000_000 / 8,
        )

    private fun cached(
        id: UUID,
        runTimeTicks: Long,
    ) = ItemEntity(
        id = id,
        name = "Other",
        sortName = "Other",
        type = ItemType.EPISODE,
        source = ItemSource.DOWNLOAD,
        cachedAt = NOW,
        runTimeTicks = runTimeTicks,
        dto = "{}",
    )

    private fun seeder() = SiblingSeeder(downloadDao = downloadDao, itemDao = itemDao, clock = clock)

    private fun enqueuer() =
        DownloadEnqueuer(
            api = api,
            itemDao = itemDao,
            downloadDao = downloadDao,
            deleter = deleter,
            mapper = mapper,
            appPreferences = appPreferences,
            seeder = seeder(),
            clock = clock,
        )

    private fun queue() =
        DownloadQueue(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = mapper,
            planner = DownloadFilePlanner(urls),
            storage = storage,
            downloader = downloader,
            seeder = seeder(),
            sweeper = sweeper,
            sessionGate = sessionGate,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private class RecordingListener : DownloadQueueListener {
        override suspend fun onProgress(
            download: DownloadEntity,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ) = Unit

        override suspend fun onIdle() = Unit
    }

    private companion object {
        val USER: UUID = uuid(99)
        val SERIES: UUID = uuid(10)
        val SEASON: UUID = uuid(11)
        val EPISODE_1: UUID = uuid(21)
        val EPISODE_2: UUID = uuid(22)
        val EPISODE_3: UUID = uuid(23)
        val EPISODE_IDS = listOf(EPISODE_1, EPISODE_2, EPISODE_3)

        const val TRANSCODE_URL = "https://server/videos/stream.mkv"
        const val IMAGE_URL = "https://server/image"

        /** One hour in `runTimeTicks` (100 ns each). */
        const val HOUR_TICKS = 36_000_000_000L

        const val MEDIA_BYTES = 200_000_000L
        const val IMAGE_BYTES = 400L

        /** What episode 1 weighs once it is on disk: the transcode plus its two images. */
        const val LANDED_BYTES = MEDIA_BYTES + 2 * IMAGE_BYTES
    }
}
