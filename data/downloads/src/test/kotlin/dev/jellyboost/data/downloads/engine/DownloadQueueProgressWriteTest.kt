package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.network.session.SessionGate
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.RecordingTransactionRunner
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.plan.DownloadFilePlanner
import dev.jellyboost.data.downloads.plan.DownloadUrlFactory
import dev.jellyboost.data.downloads.storage.DownloadStorage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

/**
 * How many database transactions one progress sample costs. The Downloads screen reads
 * `DownloadDao.observeAll()`, a `@Transaction` join, and Room's invalidation tracker fires once per
 * **committed transaction** — so two auto-commit statements per sample would re-run that whole join
 * twice, two to eight times a second for the length of a multi-gigabyte transfer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueProgressWriteTest {
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>()
    private val itemMapper = mockk<ItemEntityMapper>()
    private val storage = mockk<DownloadStorage>()
    private val downloader = mockk<FileDownloader>()
    private val seeder = mockk<SiblingSeeder>(relaxUnitFun = true)
    private val sweeper = mockk<OrphanSweeper>()
    private val urls = mockk<DownloadUrlFactory>(relaxed = true)
    private val sessionGate = mockk<SessionGate>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val listener = RecordingListener()
    private val transactions = RecordingTransactionRunner()

    /** Every progress write that landed with no transaction open around it. */
    private val looseWrites = mutableListOf<String>()

    private var nextFileId = 1L

    @BeforeEach
    fun setUp() {
        coEvery { downloadDao.updateProgress(any(), any(), any(), any(), any()) } answers {
            if (!transactions.isOpen) looseWrites += "downloads"
        }
        coEvery { downloadDao.updateFileProgress(any(), any(), any()) } answers {
            if (!transactions.isOpen) looseWrites += "download_files"
        }
        every { storage.prepareItemDirectory(any()) } returns File("/tmp/downloads")
        every { storage.resolve(any(), any()) } answers { File("/tmp/downloads/${secondArg<String>()}") }
        coEvery { downloadDao.insertFile(any()) } answers { nextFileId++ }
        coEvery { downloadDao.get(any()) } returns download()
        coEvery { itemDao.getItem(any()) } returns ITEM_ENTITY
        every { itemMapper.toDtoOrNull(any()) } returns movie()
        every { urls.mediaUrl(any()) } returns "https://server/download"
        every { urls.imageUrl(any(), any(), any(), any()) } returns "https://server/image"
        coEvery { sessionGate.ensureSession() } returns true
        coEvery { downloadDao.markDownloadingIfRunnable(any(), any()) } returns 1
        coEvery { seeder.seedFor(any(), any(), any(), any(), any()) } returns null
        coEvery { sweeper.sweep() } returns 0L
    }

    @Test
    fun `a throttled sample writes the file row and the item row inside one transaction`() =
        runTest {
            queueWith(download(bytesTotal = 10_000L))
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } coAnswers {
                // The sixth argument, not the last: MockK hands a suspending call its continuation.
                arg<ProgressCallback>(5).onProgress(300L, 1_000L)
                300L
            }

            queue().drain(listener)

            listener.progress.shouldNotBeEmpty()
            looseWrites.shouldBeEmpty()
        }

    @Test
    fun `the write that closes a finished file is inside a transaction too`() =
        runTest {
            // `completed()` reports the file's final counters and pays the same invalidation the
            // throttled samples do.
            queueWith(download(bytesTotal = 10_000L))
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } returns 100L

            queue().drain(listener)

            listener.progress.shouldNotBeEmpty()
            looseWrites.shouldBeEmpty()
        }

    private fun queue() =
        DownloadQueue(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            planner = DownloadFilePlanner(urls),
            storage = storage,
            downloader = downloader,
            extractor = FakeExtractor(),
            seeder = seeder,
            sweeper = sweeper,
            sessionGate = sessionGate,
            transactionRunner = transactions,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun queueWith(vararg downloads: DownloadEntity) {
        val queued = downloads.map { DownloadWithFiles(download = it, files = emptyList()) }
        coEvery { downloadDao.nextRunnable() } returnsMany (queued + null)
    }

    private companion object {
        val ITEM_ENTITY =
            ItemEntity(
                id = uuid(1),
                name = "Arrival",
                sortName = "Arrival",
                type = ItemType.MOVIE,
                source = ItemSource.DOWNLOAD,
                cachedAt = NOW,
                dto = "{}",
            )
    }
}
