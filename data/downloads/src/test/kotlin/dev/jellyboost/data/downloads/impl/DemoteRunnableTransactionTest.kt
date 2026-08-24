package dev.jellyboost.data.downloads.impl

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The one rule `DownloadDao` carries in Kotlin: `demoteRunnable`'s body — the `DOWNLOADING` leg's
 * count is the "was the live transfer taken?" answer, and the `QUEUED` leg always runs too. The
 * SQL guards and the `@Transaction` wrapper are Room's side of the contract (exercised on a
 * device); the body is plain Kotlin, so it is pinned on the JVM like every other queue rule — the
 * DAO's own KDoc sends rules here on purpose.
 *
 * `callOriginal()` is what lets the interface's default body run against stubbed legs.
 */
class DemoteRunnableTransactionTest {
    private val dao = mockk<DownloadDao>()
    private val ids = listOf(uuid(1), uuid(2))

    @BeforeEach
    fun setUp() {
        coEvery { dao.demoteRunnable(any(), any(), any()) } coAnswers { callOriginal() }
    }

    @Test
    fun `reports the live transfer taken when the DOWNLOADING leg moved a row`() =
        runTest {
            // This answer is what makes the caller stop the worker: the row it took *was* being
            // transferred, and nothing else will ever recheck the status mid-transfer.
            coEvery { dao.setStatusIfDownloading(ids, DownloadStatus.PAUSED, NOW) } returns 1
            coEvery { dao.setStatusIfQueued(ids, DownloadStatus.PAUSED, NOW) } returns 1

            dao.demoteRunnable(ids, DownloadStatus.PAUSED, NOW) shouldBe true
        }

    @Test
    fun `reports the live transfer untouched when only queued rows moved`() =
        runTest {
            // `false` is what spares the running worker — for a transcode, what keeps its bytes —
            // and it is trustworthy only because it was computed in the same
            // transaction as the write, where no drain claim can interleave.
            coEvery { dao.setStatusIfDownloading(ids, DownloadStatus.CANCELLED, NOW) } returns 0
            coEvery { dao.setStatusIfQueued(ids, DownloadStatus.CANCELLED, NOW) } returns 2

            dao.demoteRunnable(ids, DownloadStatus.CANCELLED, NOW) shouldBe false
        }

    @Test
    fun `runs both legs even when the transfer was taken`() =
        runTest {
            // Taking the live row must not short-circuit the queued ones: a "Pause all" batch
            // usually holds both kinds, and a queued row left QUEUED would download anyway.
            coEvery { dao.setStatusIfDownloading(ids, DownloadStatus.PAUSED, NOW) } returns 1
            coEvery { dao.setStatusIfQueued(ids, DownloadStatus.PAUSED, NOW) } returns 0

            dao.demoteRunnable(ids, DownloadStatus.PAUSED, NOW) shouldBe true

            coVerifyOrder {
                dao.setStatusIfDownloading(ids, DownloadStatus.PAUSED, NOW)
                dao.setStatusIfQueued(ids, DownloadStatus.PAUSED, NOW)
            }
        }
}
