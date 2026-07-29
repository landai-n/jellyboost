package dev.jellyfinnative.core.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.jellyfinnative.core.common.selection.SelectionAction
import dev.jellyfinnative.core.common.selection.SelectionIntent
import dev.jellyfinnative.core.ui.R

/**
 * The contextual action bar a list shows **instead of** its normal top bar while items are
 * selected (docs/features/batch-selection.md).
 *
 * Shape is the M3 contextual convention: the close affordance takes the navigation slot — the same
 * place the screen's Back sits, so the top-left corner always means "get out of here" — the title
 * is the count, and the batch actions take the `actions` slot the screen's own actions were in. The
 * `secondaryContainer` colours are what make it read as a mode rather than as the screen's bar with
 * different buttons.
 *
 * The bar is deliberately dumb: it renders a count and emits [SelectionIntent]s. Every surface that
 * offers selection passes the same one lambda, so the two screens cannot drift into offering
 * different actions in different orders.
 *
 * @param showSelectAll offered only by surfaces where "all" is a definite set. The library grid
 *   pages its content and therefore omits it; see docs/features/batch-selection.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionAppBar(
    count: Int,
    onIntent: (SelectionIntent) -> Unit,
    modifier: Modifier = Modifier,
    showSelectAll: Boolean = false,
) {
    TopAppBar(
        modifier = modifier,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        title = { Text(text = pluralStringResource(R.plurals.selection_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = { onIntent(SelectionIntent.Clear) }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.selection_close),
                )
            }
        },
        actions = {
            if (showSelectAll) {
                IconButton(onClick = { onIntent(SelectionIntent.SelectAll) }) {
                    Icon(
                        imageVector = Icons.Filled.SelectAll,
                        contentDescription = stringResource(R.string.selection_select_all),
                    )
                }
            }
            SelectionActionButton(
                action = SelectionAction.MARK_WATCHED,
                onIntent = onIntent,
            )
            SelectionActionButton(
                action = SelectionAction.MARK_UNWATCHED,
                onIntent = onIntent,
            )
            SelectionActionButton(
                action = SelectionAction.DOWNLOAD,
                onIntent = onIntent,
            )
        },
    )
}

@Composable
private fun SelectionActionButton(
    action: SelectionAction,
    onIntent: (SelectionIntent) -> Unit,
) {
    IconButton(onClick = { onIntent(SelectionIntent.Run(action)) }) {
        Icon(
            imageVector =
                when (action) {
                    SelectionAction.MARK_WATCHED -> Icons.Filled.Check
                    SelectionAction.MARK_UNWATCHED -> Icons.Filled.RemoveDone
                    SelectionAction.DOWNLOAD -> Icons.Outlined.Download
                },
            contentDescription =
                stringResource(
                    when (action) {
                        SelectionAction.MARK_WATCHED -> R.string.selection_mark_watched
                        SelectionAction.MARK_UNWATCHED -> R.string.selection_mark_unwatched
                        SelectionAction.DOWNLOAD -> R.string.selection_download
                    },
                ),
        )
    }
}
