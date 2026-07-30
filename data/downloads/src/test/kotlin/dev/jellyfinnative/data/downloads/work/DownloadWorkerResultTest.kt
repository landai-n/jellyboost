package dev.jellyfinnative.data.downloads.work

import androidx.work.ListenableWorker
import dev.jellyfinnative.data.downloads.engine.DrainOutcome
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for [toWorkerResult] — which drain outcomes come back for another run.
 *
 * The mapping is the second half of the retry policy: `DownloadQueue` decides *whether* an item
 * deserves another attempt, and this decides whether WorkManager is asked to provide one. Getting
 * it wrong in either direction is invisible in the UI — a missed `retry()` silently strands a queue
 * that was one blip away from finishing, an extra one re-runs a job over a permanently broken item
 * forever.
 */
class DownloadWorkerResultTest {
    @Test
    fun `a clean drain succeeds`() {
        DrainOutcome.COMPLETED.toWorkerResult().shouldBeInstanceOf<ListenableWorker.Result.Success>()
    }

    @Test
    fun `a permanent failure is reported, not retried`() {
        // The row is already ERROR in Room and rendered as such; re-running the job would loop on
        // an item that cannot succeed (deleted on the server, a folder queued as a file).
        DrainOutcome.INCOMPLETE.toWorkerResult().shouldBeInstanceOf<ListenableWorker.Result.Success>()
    }

    @Test
    fun `a transient failure asks WorkManager for another run`() {
        // The whole of STAB-01's fix depends on this line: the queue left the row QUEUED and
        // counted the attempt, and the only thing that can start the next one is WorkManager's own
        // EXPONENTIAL/30 s backoff.
        DrainOutcome.RETRY.toWorkerResult().shouldBeInstanceOf<ListenableWorker.Result.Retry>()
    }

    @Test
    fun `a queue with no session is retried rather than reported`() {
        DrainOutcome.NO_SESSION.toWorkerResult().shouldBeInstanceOf<ListenableWorker.Result.Retry>()
    }
}
