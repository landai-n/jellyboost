package dev.jellyboost.data.downloads.impl

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.ItemParentRefs
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.storage.DownloadStorage
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
        coEvery { downloadDao.deleteUnlessRunnable(any()) } returns 1
        coEvery { downloadDao.allItemIds() } returns emptyList()
        coEvery { itemDao.getParentRefs(any()) } returns emptyList()
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
    fun `the guarded row delete goes before the files`() =
        runTest {
            // The order the plan's cascade gives is the other one, and audit CORR-1 is why it
            // changed: the guard has to be the first destructive act or it guards nothing. What
            // the old order protected against — a process death leaving bytes nothing points at —
            // is `OrphanSweeper`'s job at the head of every drain.
            deleter().delete(uuid(1))

            coVerifyOrder {
                downloadDao.deleteUnlessRunnable(uuid(1))
                storage.deleteItemDirectory(any())
            }
        }

    @Test
    fun `a download re-enqueued while the cascade waited keeps its row, its metadata and its files`() =
        runTest {
            // The CORR-1 interleaving, at the seam that decides it. The user cancelled (row
            // CANCELLED, UI instantly offers *Download*), the cascade is behind a five-second
            // `stop()`, and the user re-tapped: `DownloadEnqueuer` has written a fresh QUEUED row,
            // so the guarded delete matches nothing and this item is not this cascade's any more.
            coEvery { downloadDao.deleteUnlessRunnable(uuid(1)) } returns 0

            deleter().delete(uuid(1)) shouldBe 0L

            // Not one byte, not one row, not one metadata blob of the new download is touched.
            verify(exactly = 0) { storage.deleteItemDirectory(any()) }
            coVerify(exactly = 0) { itemDao.deleteDownloadsNotIn(any(), any()) }
            coVerify(exactly = 0) { downloadDao.deleteSyncedUserData(any()) }
        }

    @Test
    fun `a batch deletes the rows that are still its own and skips the one that came back`() =
        runTest {
            // Cancel-all on a season while the user re-taps Download on one episode: the other
            // rows still go, and the prune still runs once for them.
            coEvery { downloadDao.get(uuid(1)) } returns download(itemId = uuid(1))
            coEvery { downloadDao.get(uuid(2)) } returns download(itemId = uuid(2), directoryName = "Dune (2021)")
            coEvery { downloadDao.deleteUnlessRunnable(uuid(2)) } returns 0
            every { storage.deleteItemDirectory("Arrival (2016)") } returns 100L

            deleter().deleteAll(listOf(uuid(1), uuid(2))) shouldBe 100L

            verify(exactly = 0) { storage.deleteItemDirectory("Dune (2021)") }
            coVerify(exactly = 1) { downloadDao.deleteSyncedUserData(uuid(1)) }
            coVerify(exactly = 0) { downloadDao.deleteSyncedUserData(uuid(2)) }
            coVerify(exactly = 1) { itemDao.deleteDownloadsNotIn(any(), any()) }
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
                // `download_files` follows through the foreign key.
                downloadDao.deleteUnlessRunnable(uuid(1))
                itemDao.deleteDownloadsNotIn(any(), ItemSource.DOWNLOAD)
                downloadDao.deleteSyncedUserData(uuid(1))
                storage.deleteItemDirectory("Arrival (2016)")
            }
        }

    @Test
    fun `a failing file delete still removes the rows`() =
        runTest {
            every { storage.deleteItemDirectory(any()) } throws IllegalStateException("volume ejected")

            deleter().delete(uuid(1)) shouldBe 0L

            coVerify { downloadDao.deleteUnlessRunnable(uuid(1)) }
        }

    @Test
    fun `deleting something that was never downloaded is a no-op`() =
        runTest {
            coEvery { downloadDao.get(uuid(1)) } returns null

            deleter().delete(uuid(1)) shouldBe 0L

            coVerify(exactly = 0) { downloadDao.deleteUnlessRunnable(any()) }
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
            coEvery { itemDao.getParentRefs(listOf(uuid(3))) } returns
                listOf(parentRefs(id = uuid(3), seriesId = uuid(10), seasonId = uuid(11)))

            deleter().delete(uuid(2))

            kept.captured shouldContainExactlyInAnyOrder listOf(uuid(3), uuid(10), uuid(11))
        }

    @Test
    fun `the deleted item is not in the surviving set`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(3))
            coEvery { itemDao.getParentRefs(any()) } returns listOf(parentRefs(id = uuid(3)))

            deleter().delete(uuid(2))

            kept.captured shouldNotContain uuid(2)
        }

    @Test
    fun `a movie's own row is kept while its download exists`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(5))
            coEvery { itemDao.getParentRefs(listOf(uuid(5))) } returns
                listOf(parentRefs(id = uuid(5), seriesId = null, seasonId = null))

            deleter().delete(uuid(1))

            kept.captured shouldContainExactlyInAnyOrder listOf(uuid(5))
        }

    // ---- the batch cascade (DL-05) --------------------------------------------------------------

    @Test
    fun `a bulk delete runs the metadata prune once, not once per row`() =
        runTest {
            // Cancel all on a 40-row queue used to re-read every surviving download's whole
            // metadata blob once per deleted row — O(deleted × remaining) blob reads with the UI
            // waiting on the result.
            coEvery { downloadDao.get(uuid(1)) } returns download(itemId = uuid(1))
            coEvery { downloadDao.get(uuid(2)) } returns download(itemId = uuid(2), directoryName = "Dune (2021)")
            every { storage.deleteItemDirectory("Arrival (2016)") } returns 100L
            every { storage.deleteItemDirectory("Dune (2021)") } returns 200L

            deleter().deleteAll(listOf(uuid(1), uuid(2))) shouldBe 300L

            coVerify(exactly = 1) { itemDao.deleteDownloadsNotIn(any(), any()) }
            coVerify(exactly = 1) { downloadDao.deleteUnlessRunnable(uuid(1)) }
            coVerify(exactly = 1) { downloadDao.deleteUnlessRunnable(uuid(2)) }
            coVerify(exactly = 1) { downloadDao.deleteSyncedUserData(uuid(1)) }
            coVerify(exactly = 1) { downloadDao.deleteSyncedUserData(uuid(2)) }
        }

    @Test
    fun `a bulk delete with nothing to remove never runs the prune`() =
        runTest {
            coEvery { downloadDao.get(any()) } returns null

            deleter().deleteAll(listOf(uuid(1), uuid(2))) shouldBe 0L

            coVerify(exactly = 0) { itemDao.deleteDownloadsNotIn(any(), any()) }
            coVerify(exactly = 0) { downloadDao.deleteSyncedUserData(any()) }
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

    private fun deleter() =
        DownloadDeleter(
            downloadDao = downloadDao,
            itemDao = itemDao,
            storage = storage,
            transactionRunner = DownloadFixtures.directTransactionRunner,
        )

    private fun parentRefs(
        id: UUID,
        seriesId: UUID? = uuid(10),
        seasonId: UUID? = uuid(11),
    ) = ItemParentRefs(id = id, parentId = null, seriesId = seriesId, seasonId = seasonId)
}
