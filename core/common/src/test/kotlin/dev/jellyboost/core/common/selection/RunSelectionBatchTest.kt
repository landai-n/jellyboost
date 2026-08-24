package dev.jellyboost.core.common.selection

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RunSelectionBatchTest {
    private val enqueued = mutableListOf<String>()
    private val played = mutableListOf<Pair<String, Boolean>>()

    /** Ids the fake repositories reject, so a test can ask for a failure. */
    private var rejected: Set<String> = emptySet()

    private val setPlayed: suspend (String, Boolean) -> AppResult<*> = { id, value ->
        played += id to value
        result(id)
    }

    private val enqueue: suspend (String) -> AppResult<*> = { id ->
        enqueued += id
        result(id)
    }

    private fun result(id: String): AppResult<Unit> =
        if (id in rejected) AppResult.Failure(AppError.Network()) else AppResult.Success(Unit)

    @Test
    @DisplayName("mark watched attempts every id and counts the failures")
    fun markWatchedCountsFailures() =
        runTest {
            rejected = setOf("b")

            val outcome =
                runSelectionBatch(
                    action = SelectionAction.MARK_WATCHED,
                    ids = listOf("a", "b", "c"),
                    downloadStates = emptyMap(),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            // Never stops at the first failure — every id is attempted.
            played shouldBe listOf("a" to true, "b" to true, "c" to true)
            outcome shouldBe BatchOutcome(done = 2, failed = 1, skipped = 0)
        }

    @Test
    @DisplayName("mark unwatched passes played = false")
    fun markUnwatchedPassesFalse() =
        runTest {
            val outcome =
                runSelectionBatch(
                    action = SelectionAction.MARK_UNWATCHED,
                    ids = listOf("a"),
                    downloadStates = emptyMap(),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            played shouldBe listOf("a" to false)
            outcome shouldBe BatchOutcome(done = 1)
        }

    @Test
    @DisplayName("watched ignores the download states entirely")
    fun watchedIgnoresDownloadStates() =
        runTest {
            val outcome =
                runSelectionBatch(
                    action = SelectionAction.MARK_WATCHED,
                    ids = listOf("a", "b"),
                    downloadStates = mapOf("a" to DownloadState.Downloaded, "b" to DownloadState.Queued),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            // Nothing is skipped: "already downloaded" says nothing about "already watched".
            outcome shouldBe BatchOutcome(done = 2)
        }

    @Test
    @DisplayName("download skips what is already on the device or already queued")
    fun downloadSkipsWhatIsAlreadyThere() =
        runTest {
            val outcome =
                runSelectionBatch(
                    action = SelectionAction.DOWNLOAD,
                    ids = listOf("done", "queued", "paused", "fresh"),
                    downloadStates =
                        mapOf(
                            "done" to DownloadState.Downloaded,
                            "queued" to DownloadState.Queued,
                            "paused" to DownloadState.Paused,
                        ),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            enqueued shouldBe listOf("fresh")
            // Skipped, not failed: the user asked for them to be downloaded and they are.
            outcome shouldBe BatchOutcome(done = 1, failed = 0, skipped = 3)
        }

    @Test
    @DisplayName("download retries a failed item rather than skipping it")
    fun downloadRetriesAFailedItem() =
        runTest {
            val outcome =
                runSelectionBatch(
                    action = SelectionAction.DOWNLOAD,
                    ids = listOf("broken"),
                    downloadStates = mapOf("broken" to DownloadState.Failed),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            // Re-enqueueing is how a failure is retried, so it is a target and not a skip.
            enqueued shouldBe listOf("broken")
            outcome shouldBe BatchOutcome(done = 1, skipped = 0)
        }

    @Test
    @DisplayName("a container the map does not mention is always enqueued — the series carve-out")
    fun containersAreAlwaysEnqueued() =
        runTest {
            // A series or season has no download row of its own; the pipeline expands it into episodes and
            // skips there, so the absent id has to read as downloadable however much is on the device.
            val outcome =
                runSelectionBatch(
                    action = SelectionAction.DOWNLOAD,
                    ids = listOf("series-1"),
                    downloadStates = mapOf("episode-1" to DownloadState.Downloaded),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            enqueued shouldBe listOf("series-1")
            outcome shouldBe BatchOutcome(done = 1, skipped = 0)
        }

    @Test
    @DisplayName("skipped and failed are counted separately in one batch")
    fun skippedAndFailedAreDistinct() =
        runTest {
            rejected = setOf("offline")

            val outcome =
                runSelectionBatch(
                    action = SelectionAction.DOWNLOAD,
                    ids = listOf("done", "offline", "fresh"),
                    downloadStates = mapOf("done" to DownloadState.Downloaded),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            outcome shouldBe BatchOutcome(done = 1, failed = 1, skipped = 1)
        }

    @Test
    @DisplayName("a selection with nothing left to download reports only skips")
    fun everythingSkippedReportsNoWork() =
        runTest {
            val outcome =
                runSelectionBatch(
                    action = SelectionAction.DOWNLOAD,
                    ids = listOf("a", "b"),
                    downloadStates =
                        mapOf("a" to DownloadState.Downloaded, "b" to DownloadState.Downloaded),
                    setPlayed = setPlayed,
                    enqueue = enqueue,
                )

            enqueued shouldBe emptyList()
            outcome shouldBe BatchOutcome(done = 0, failed = 0, skipped = 2)
        }
}
