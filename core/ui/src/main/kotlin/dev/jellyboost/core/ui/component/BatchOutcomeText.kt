package dev.jellyboost.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.selection.BatchOutcome
import dev.jellyboost.core.common.selection.SelectionAction
import dev.jellyboost.core.ui.R

/** Turns [resolveBatchMessage]'s decision into a string; the decision itself stays Android-free. */
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

/** Ladder order matters: all-failed before mixed, and the skipped shapes are *Download*-only. */
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
