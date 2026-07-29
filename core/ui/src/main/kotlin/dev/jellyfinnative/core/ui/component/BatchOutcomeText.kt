package dev.jellyfinnative.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.jellyfinnative.core.common.selection.BatchOutcome
import dev.jellyfinnative.core.common.selection.SelectionAction
import dev.jellyfinnative.core.ui.R

/**
 * The one snackbar line a finished batch produces.
 *
 * Resolved in Compose, from a resource-free [BatchOutcome], for the reason every message type in
 * this app is: a `ViewModel` that formatted its own copy could not be translated and could not be
 * unit-tested without Android. Both surfaces call this, so "Marked 4 watched, 1 failed" reads the
 * same in the library grid and on a season page.
 *
 * Four shapes, in the order they are decided:
 * 1. **nothing succeeded** — say so plainly rather than reporting a zero ("Marked 0 watched, 3
 *    failed" is a sentence no one should have to parse);
 * 2. **mixed** — both numbers, because the difference is the whole point of a bulk action;
 * 3. **nothing to do** — *Download* only: every selected item was already on the device;
 * 4. **clean** — the count, plus what was skipped when anything was.
 */
@Composable
fun batchOutcomeText(
    action: SelectionAction,
    outcome: BatchOutcome,
): String {
    if (outcome.failed > 0) {
        return if (outcome.done == 0) {
            pluralStringResource(action.allFailedPlural(), outcome.failed, outcome.failed)
        } else {
            stringResource(action.partialString(), outcome.done, outcome.failed)
        }
    }

    if (action == SelectionAction.DOWNLOAD) {
        if (outcome.done == 0 && outcome.skipped > 0) {
            return pluralStringResource(
                R.plurals.selection_result_download_all_skipped,
                outcome.skipped,
                outcome.skipped,
            )
        }
        if (outcome.skipped > 0) {
            return stringResource(
                R.string.selection_result_download_skipped,
                outcome.done,
                outcome.skipped,
            )
        }
    }

    return pluralStringResource(action.donePlural(), outcome.done, outcome.done)
}

private fun SelectionAction.donePlural(): Int =
    when (this) {
        SelectionAction.MARK_WATCHED -> R.plurals.selection_result_watched
        SelectionAction.MARK_UNWATCHED -> R.plurals.selection_result_unwatched
        SelectionAction.DOWNLOAD -> R.plurals.selection_result_download
    }

private fun SelectionAction.partialString(): Int =
    when (this) {
        SelectionAction.MARK_WATCHED -> R.string.selection_result_watched_partial
        SelectionAction.MARK_UNWATCHED -> R.string.selection_result_unwatched_partial
        SelectionAction.DOWNLOAD -> R.string.selection_result_download_partial
    }

private fun SelectionAction.allFailedPlural(): Int =
    when (this) {
        SelectionAction.MARK_WATCHED -> R.plurals.selection_result_watched_failed
        SelectionAction.MARK_UNWATCHED -> R.plurals.selection_result_unwatched_failed
        SelectionAction.DOWNLOAD -> R.plurals.selection_result_download_failed
    }
