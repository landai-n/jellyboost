package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.data.downloads.storage.DownloadStorage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * The bytes this deletes are invisible in the UI — no row lists them and no delete reaches them — so
 * the only thing between "cleans up after a cancelled transfer" and "eats a download the user is still
 * waiting for" is the claimed-name check. Both directions are pinned here.
 */
class OrphanSweeperTest {
    private val downloadDao = mockk<DownloadDao>()
    private val storage = mockk<DownloadStorage>(relaxed = true)

    @BeforeEach
    fun setUp() {
        coEvery { downloadDao.allDirectoryNames() } returns emptyList()
        every { storage.itemDirectoryNames() } returns emptyList()
        every { storage.deleteItemDirectory(any()) } returns 0L
    }

    @Test
    fun `a directory no row claims is removed`() =
        runTest {
            // What a cancel landing inside the media file leaves behind: the cascade unlinked the
            // directory, and the still-running downloader put it straight back with `mkdirs()`.
            coEvery { downloadDao.allDirectoryNames() } returns listOf("Arrival (2016)")
            every { storage.itemDirectoryNames() } returns listOf("Arrival (2016)", "Dune (2021)")
            every { storage.deleteItemDirectory("Dune (2021)") } returns 1_400_000_000L

            sweeper().sweep() shouldBe 1_400_000_000L

            verify(exactly = 1) { storage.deleteItemDirectory("Dune (2021)") }
        }

    @Test
    fun `a directory a row still claims is never touched`() =
        runTest {
            // Including the row about to be downloaded: the sweep runs at the head of a drain, so
            // every queued item's directory is claimed but mostly empty.
            coEvery { downloadDao.allDirectoryNames() } returns listOf("Arrival (2016)", "Dune (2021)")
            every { storage.itemDirectoryNames() } returns listOf("Arrival (2016)", "Dune (2021)")

            sweeper().sweep() shouldBe 0L

            verify(exactly = 0) { storage.deleteItemDirectory(any()) }
        }

    @Test
    fun `an unmounted volume sweeps nothing rather than everything`() =
        runTest {
            // The failure mode to avoid is the mirror image, where an unreadable *table* makes every
            // file on disk look orphaned.
            coEvery { downloadDao.allDirectoryNames() } returns listOf("Arrival (2016)")
            every { storage.itemDirectoryNames() } returns emptyList()

            sweeper().sweep() shouldBe 0L

            verify(exactly = 0) { storage.deleteItemDirectory(any()) }
        }

    @Test
    fun `a failing sweep is swallowed, because a drain must still run`() =
        runTest {
            // A queue that refuses to download because a stale directory could not be removed would
            // be a worse bug than the leak.
            coEvery { downloadDao.allDirectoryNames() } throws IOException("room is busy")

            sweeper().sweep() shouldBe 0L
        }

    @Test
    fun `a cancellation is rethrown rather than logged as a failed sweep`() =
        runTest {
            coEvery { downloadDao.allDirectoryNames() } throws CancellationException("worker stopped")

            assertThrows<CancellationException> { sweeper().sweep() }
        }

    @Test
    fun `the table is read once, however many directories are on disk`() =
        runTest {
            coEvery { downloadDao.allDirectoryNames() } returns emptyList()
            every { storage.itemDirectoryNames() } returns listOf("a", "b", "c")

            sweeper().sweep()

            coVerify(exactly = 1) { downloadDao.allDirectoryNames() }
        }

    private fun sweeper() = OrphanSweeper(downloadDao = downloadDao, storage = storage)
}
