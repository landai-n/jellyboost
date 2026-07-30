package dev.jellyfinnative.core.ui.component

import dev.jellyfinnative.core.common.selection.BatchOutcome
import dev.jellyfinnative.core.common.selection.SelectionAction
import dev.jellyfinnative.core.ui.R
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for [resolveBatchMessage] — `:core:ui`'s first (docs/notes/audit-2026-07.md, ARCH-07;
 * this module had none before it).
 *
 * The ladder is pulled out of the `@Composable` `batchOutcomeText` precisely so the six outcomes it
 * has to tell apart can be pinned here, with no Android dependency in the way.
 */
class BatchOutcomeTextTest {
    // ---- the message ladder ------------------------------------------------------------------------

    @Test
    fun `nothing succeeding is reported plainly, not as a zero`() {
        val message = resolveBatchMessage(SelectionAction.MARK_WATCHED, BatchOutcome(done = 0, failed = 3))

        message.shouldBeInstanceOf<BatchMessage.AllFailed>().failed shouldBe 3
    }

    @Test
    fun `a mixed result carries both the done and the failed count`() {
        val message = resolveBatchMessage(SelectionAction.MARK_UNWATCHED, BatchOutcome(done = 4, failed = 1))

        val partial = message.shouldBeInstanceOf<BatchMessage.Partial>()
        partial.done shouldBe 4
        partial.failed shouldBe 1
    }

    @Test
    fun `a download batch that was entirely already on the device says so, not zero downloaded`() {
        val message = resolveBatchMessage(SelectionAction.DOWNLOAD, BatchOutcome(done = 0, skipped = 5))

        message.shouldBeInstanceOf<BatchMessage.AllSkipped>().skipped shouldBe 5
    }

    @Test
    fun `a download batch that was partly already on the device carries both counts`() {
        val message = resolveBatchMessage(SelectionAction.DOWNLOAD, BatchOutcome(done = 2, skipped = 3))

        val doneWithSkipped = message.shouldBeInstanceOf<BatchMessage.DoneWithSkipped>()
        doneWithSkipped.done shouldBe 2
        doneWithSkipped.skipped shouldBe 3
    }

    @Test
    fun `a clean download batch is just the count, like any other clean batch`() {
        val message = resolveBatchMessage(SelectionAction.DOWNLOAD, BatchOutcome(done = 6))

        message.shouldBeInstanceOf<BatchMessage.Done>().done shouldBe 6
    }

    @Test
    fun `a clean non-download batch is just the count`() {
        val message = resolveBatchMessage(SelectionAction.MARK_WATCHED, BatchOutcome(done = 6))

        message.shouldBeInstanceOf<BatchMessage.Done>().done shouldBe 6
    }

    @Test
    fun `skipped items are ignored for an action that has none, unlike Download`() {
        // Only Download can skip an item; a non-Download outcome that somehow carried a skipped
        // count must not be read as "some done, some skipped" — there is no such sentence for it.
        val message = resolveBatchMessage(SelectionAction.MARK_WATCHED, BatchOutcome(done = 2, skipped = 3))

        message.shouldBeInstanceOf<BatchMessage.Done>().done shouldBe 2
    }

    // ---- the three plural/partial mappings ---------------------------------------------------------

    @Test
    fun `each action's done plural is its own resource`() {
        SelectionAction.MARK_WATCHED.donePlural() shouldBe R.plurals.selection_result_watched
        SelectionAction.MARK_UNWATCHED.donePlural() shouldBe R.plurals.selection_result_unwatched
        SelectionAction.DOWNLOAD.donePlural() shouldBe R.plurals.selection_result_download
    }

    @Test
    fun `each action's partial string is its own resource`() {
        SelectionAction.MARK_WATCHED.partialString() shouldBe R.string.selection_result_watched_partial
        SelectionAction.MARK_UNWATCHED.partialString() shouldBe R.string.selection_result_unwatched_partial
        SelectionAction.DOWNLOAD.partialString() shouldBe R.string.selection_result_download_partial
    }

    @Test
    fun `each action's all-failed plural is its own resource`() {
        SelectionAction.MARK_WATCHED.allFailedPlural() shouldBe R.plurals.selection_result_watched_failed
        SelectionAction.MARK_UNWATCHED.allFailedPlural() shouldBe R.plurals.selection_result_unwatched_failed
        SelectionAction.DOWNLOAD.allFailedPlural() shouldBe R.plurals.selection_result_download_failed
    }
}
