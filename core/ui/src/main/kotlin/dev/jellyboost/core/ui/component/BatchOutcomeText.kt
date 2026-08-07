package dev.jellyboost.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.selection.BatchOutcome
import dev.jellyboost.core.common.selection.SelectionAction
import dev.jellyboost.core.ui.R

/**
 * The one snackbar line a finished batch produces.
 *
 * Resolved in Compose, from a resource-free [BatchOutcome], for the reason every message type in
 * this app is: a `ViewModel` that formatted its own copy could not be translated and could not be
 * unit-tested without Android. Both surfaces call this, so "Marked 4 watched, 1 failed" reads the
 * same in the library grid and on a season page.
 *
 * *Which* shape applies is [resolveBatchMessage] — a plain function with no Android dependency, and
 * the thing worth pinning: `:core:ui` had no tests at all before it (docs/notes/audit-2026-07.md,
 * ARCH-07). This composable's only job is turning that decision into a string.
 */
@Composable
fun batchOutcomeText(
    action: SelectionAction,
    outcome: BatchOutcome,
): String =
    when (val message = resolveBatchMessage(action, outcome)) {
        is BatchMessage.AllFailed ->
            pluralStringResource(action.allFailedPlural(), message.failed, message.failed)

        is BatchMessage.Partial ->
            stringResource(action.partialString(), message.done, message.failed)

        is BatchMessage.AllSkipped ->
            pluralStringResource(
                R.plurals.selection_result_download_all_skipped,
                message.skipped,
                message.skipped,
            )

        is BatchMessage.DoneWithSkipped ->
            stringResource(R.string.selection_result_download_skipped, message.done, message.skipped)

        is BatchMessage.Done ->
            pluralStringResource(action.donePlural(), message.done, message.done)
    }

/**
 * Which shape of batch-outcome sentence applies, decided from the three raw counts alone.
 *
 * In the order they are decided:
 * 1. **nothing succeeded** ([BatchMessage.AllFailed]) — say so plainly rather than reporting a zero
 *    ("Marked 0 watched, 3 failed" is a sentence no one should have to parse);
 * 2. **mixed** ([BatchMessage.Partial]) — both numbers, because the difference is the whole point
 *    of a bulk action;
 * 3. **nothing to do** ([BatchMessage.AllSkipped]) — *Download* only: every selected item was
 *    already on the device;
 * 4. **some done, some skipped** ([BatchMessage.DoneWithSkipped]) — *Download* only;
 * 5. **clean** ([BatchMessage.Done]) — the count, nothing failed or skipped.
 */
@Suppress(
    // One resource per outcome shape; a `when` over two independent counts would nest, not flatten.
    "ReturnCount",
)
internal fun resolveBatchMessage(
    action: SelectionAction,
    outcome: BatchOutcome,
): BatchMessage {
    if (outcome.failed > 0) {
        return if (outcome.done == 0) {
            BatchMessage.AllFailed(outcome.failed)
        } else {
            BatchMessage.Partial(outcome.done, outcome.failed)
        }
    }

    if (action == SelectionAction.DOWNLOAD) {
        if (outcome.done == 0 && outcome.skipped > 0) return BatchMessage.AllSkipped(outcome.skipped)
        if (outcome.skipped > 0) return BatchMessage.DoneWithSkipped(outcome.done, outcome.skipped)
    }

    return BatchMessage.Done(outcome.done)
}

/** The resource-free shapes [resolveBatchMessage] can decide on — see its KDoc for the ladder. */
internal sealed interface BatchMessage {
    data class AllFailed(
        val failed: Int,
    ) : BatchMessage

    data class Partial(
        val done: Int,
        val failed: Int,
    ) : BatchMessage

    data class AllSkipped(
        val skipped: Int,
    ) : BatchMessage

    data class DoneWithSkipped(
        val done: Int,
        val skipped: Int,
    ) : BatchMessage

    data class Done(
        val done: Int,
    ) : BatchMessage
}

internal fun SelectionAction.donePlural(): Int =
    when (this) {
        SelectionAction.MARK_WATCHED -> R.plurals.selection_result_watched
        SelectionAction.MARK_UNWATCHED -> R.plurals.selection_result_unwatched
        SelectionAction.DOWNLOAD -> R.plurals.selection_result_download
    }

internal fun SelectionAction.partialString(): Int =
    when (this) {
        SelectionAction.MARK_WATCHED -> R.string.selection_result_watched_partial
        SelectionAction.MARK_UNWATCHED -> R.string.selection_result_unwatched_partial
        SelectionAction.DOWNLOAD -> R.string.selection_result_download_partial
    }

internal fun SelectionAction.allFailedPlural(): Int =
    when (this) {
        SelectionAction.MARK_WATCHED -> R.plurals.selection_result_watched_failed
        SelectionAction.MARK_UNWATCHED -> R.plurals.selection_result_unwatched_failed
        SelectionAction.DOWNLOAD -> R.plurals.selection_result_download_failed
    }
