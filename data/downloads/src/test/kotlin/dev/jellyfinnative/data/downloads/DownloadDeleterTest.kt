package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.download
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [DownloadDeleter] — docs/PLAN.md's delete cascade.
 *
 * The interesting rule is the one that is easy to get catastrophically wrong: deleting one episode
 * of a show must prune the metadata nothing needs any more **without** taking the series and season
 * rows its surviving siblings still open from.
 */
class DownloadDeleterTest {
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>()
    private val storage = mockk<DownloadStorage>()

    private val kept = slot<List<UUID>>()

    @BeforeEach
    fun setUp() {
        every { storage.deleteItemDirectory(any()) } returns 0L
        coEvery { downloadDao.get(any()) } returns download()
        coEvery { downloadDao.allItemIds() } returns emptyList()
        coEvery { itemDao.getItems(any()) } returns emptyList()
        coEvery { itemDao.deleteDownloadsNotIn(capture(kept), any()) } returns 0
    }

    // ---- the files ------------------------------------------------------------------------------

    @Test
    fun `the item's directory is removed and the freed bytes reported`() =
        runTest {
            every { storage.deleteItemDirectory("Arrival (2016)") } returns 2_100_000_000L

            deleter().delete(uuid(1)) shouldBe 2_100_000_000L
        }

    @Test
    fun `the files go before the rows`() =
        runTest {
            // The other order would leave gigabytes on disk that nothing points at if the process
            // died in between.
            deleter().delete(uuid(1))

            coVerifyOrder {
                storage.deleteItemDirectory(any())
                downloadDao.delete(uuid(1))
            }
        }

    @Test
    fun `cancelling a half-downloaded item removes its files, its rows and its metadata`() =
        runTest {
            // The whole cascade in one test, for the case the Queue tab's Cancel action produces:
            // a row that is still transferring, with a multi-gigabyte partial file on disk.
            coEvery { downloadDao.get(uuid(1)) } returns
                download(status = DownloadStatus.DOWNLOADING, bytesDownloaded = 2_400_000_000L)
            every { storage.deleteItemDirectory("Arrival (2016)") } returns 2_400_000_000L

            deleter().delete(uuid(1)) shouldBe 2_400_000_000L

            coVerifyOrder {
                storage.deleteItemDirectory("Arrival (2016)")
                // `download_files` follows through the foreign key.
                downloadDao.delete(uuid(1))
                itemDao.deleteDownloadsNotIn(any(), ItemSource.DOWNLOAD)
                downloadDao.deleteSyncedUserData(uuid(1))
            }
        }

    @Test
    fun `a failing file delete still removes the rows`() =
        runTest {
            every { storage.deleteItemDirectory(any()) } throws IllegalStateException("volume ejected")

            deleter().delete(uuid(1)) shouldBe 0L

            coVerify { downloadDao.delete(uuid(1)) }
        }

    @Test
    fun `deleting something that was never downloaded is a no-op`() =
        runTest {
            coEvery { downloadDao.get(uuid(1)) } returns null

            deleter().delete(uuid(1)) shouldBe 0L

            coVerify(exactly = 0) { downloadDao.delete(any()) }
        }

    // ---- the metadata prune ---------------------------------------------------------------------

    @Test
    fun `deleting the last download prunes every downloaded item row`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns emptyList()

            deleter().delete(uuid(1))

            kept.captured shouldBe emptyList()
            coVerify { itemDao.deleteDownloadsNotIn(emptyList(), ItemSource.DOWNLOAD) }
        }

    @Test
    fun `a surviving episode keeps its series and season rows alive`() =
        runTest {
            // uuid(3) is another episode of the same show; its series (10) and season (11) must
            // survive the deletion of uuid(2).
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(3))
            coEvery { itemDao.getItems(listOf(uuid(3))) } returns
                listOf(episodeRow(id = uuid(3), seriesId = uuid(10), seasonId = uuid(11)))

            deleter().delete(uuid(2))

            kept.captured shouldContainExactlyInAnyOrder listOf(uuid(3), uuid(10), uuid(11))
        }

    @Test
    fun `the deleted item is not in the surviving set`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(3))
            coEvery { itemDao.getItems(any()) } returns listOf(episodeRow(id = uuid(3)))

            deleter().delete(uuid(2))

            kept.captured shouldNotContain uuid(2)
        }

    @Test
    fun `a movie's own row is kept while its download exists`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(5))
            coEvery { itemDao.getItems(listOf(uuid(5))) } returns
                listOf(episodeRow(id = uuid(5), seriesId = null, seasonId = null))

            deleter().delete(uuid(1))

            kept.captured shouldContainExactlyInAnyOrder listOf(uuid(5))
        }

    // ---- user data ------------------------------------------------------------------------------

    @Test
    fun `the local user-data row is dropped unless it still owes the server a change`() =
        runTest {
            deleter().delete(uuid(1))

            // The DAO query itself carries the `toBeSynced = 0` guard — a pending row is the only
            // copy of a change the server has not seen.
            coVerify(exactly = 1) { downloadDao.deleteSyncedUserData(uuid(1)) }
        }

    // ---- helpers --------------------------------------------------------------------------------

    private fun deleter() = DownloadDeleter(downloadDao = downloadDao, itemDao = itemDao, storage = storage)

    private fun episodeRow(
        id: UUID,
        seriesId: UUID? = uuid(10),
        seasonId: UUID? = uuid(11),
    ) = ItemEntity(
        id = id,
        name = "Chestnut",
        sortName = "Chestnut",
        type = ItemType.EPISODE,
        source = ItemSource.DOWNLOAD,
        cachedAt = NOW,
        seriesId = seriesId,
        seasonId = seasonId,
        dto = "{}",
    )
}
