package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.selection.SelectionAction
import dev.jellyboost.core.common.selection.SelectionIntent
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.popShadow

/** Height of the floating bar — a comfortable touch strip, shorter than an M3 app bar. */
private val SelectionBarHeight = 60.dp

private val SelectionBarPadding = 8.dp

private val SelectionCountLabel =
    TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.W600,
    )

/**
 * The contextual action bar a list shows **instead of** its normal top bar while items are
 * selected.
 *
 * A floating glass pill rather than a `TopAppBar`: the refresh has no opaque bars left for a
 * contextual one to imitate, so the mode announces itself by *shape* — a bar that hovers over the
 * content instead of capping it. The arrangement still follows the M3 contextual convention: the
 * close affordance takes the navigation slot — the same place the screen's Back sits, so the
 * top-left corner always means "get out of here" — the count is the title, and the batch actions
 * take the trailing slot the screen's own actions were in.
 *
 * It carries its own status-bar inset, because there is no `TopAppBar` to supply one and
 * both call sites (a `Scaffold` top bar, and an overlay on the detail screen) draw at the very top
 * of the window.
 *
 * The bar is deliberately dumb: it renders a count and emits [SelectionIntent]s. Every surface that
 * offers selection passes the same one lambda, so the two screens cannot drift into offering
 * different actions in different orders.
 *
 * @param showSelectAll offered only by surfaces where "all" is a definite set. The library grid
 *   pages its content and therefore omits it.
 */
@Composable
fun SelectionAppBar(
    count: Int,
    onIntent: (SelectionIntent) -> Unit,
    modifier: Modifier = Modifier,
    showSelectAll: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall)
                // A *minimum*, not a fixed height (see `GlassBottomNav`): the count label is the
                // bar's whole purpose, and at accessibility font scales a hard 60dp clipped it.
                // The bar floats over the list, so growing costs the content nothing.
                .heightIn(min = SelectionBarHeight)
                .popShadow(CircleShape)
                .glassSurface(CircleShape)
                .padding(horizontal = SelectionBarPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        GlassIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.selection_close),
            onClick = { onIntent(SelectionIntent.Clear) },
        )
        val countLabel = pluralStringResource(R.plurals.selection_count, count, count)
        Text(
            text = countLabel,
            style = SelectionCountLabel,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The count is the one thing this bar exists to report, and every tap on a card
            // changes it. A polite live region is what turns "4 selected" from something you have
            // to go and look for into something you are told; the description is the untruncated
            // string, because at narrow widths the visible one ellipsizes to "4 sel…".
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.SpaceSmall)
                    .semantics {
                        contentDescription = countLabel
                        liveRegion = LiveRegionMode.Polite
                    },
        )
        if (showSelectAll) {
            GlassIconButton(
                icon = Icons.Filled.SelectAll,
                contentDescription = stringResource(R.string.selection_select_all),
                onClick = { onIntent(SelectionIntent.SelectAll) },
            )
        }
        SelectionActionButton(action = SelectionAction.MARK_WATCHED, onIntent = onIntent)
        SelectionActionButton(action = SelectionAction.MARK_UNWATCHED, onIntent = onIntent)
        SelectionActionButton(action = SelectionAction.DOWNLOAD, onIntent = onIntent)
    }
}

@Composable
private fun SelectionActionButton(
    action: SelectionAction,
    onIntent: (SelectionIntent) -> Unit,
) {
    GlassIconButton(
        icon =
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
        onClick = { onIntent(SelectionIntent.Run(action)) },
    )
}

@Preview(name = "SelectionAppBar", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun SelectionAppBarPreview() {
    JellyfinTheme {
        SelectionAppBar(count = 4, onIntent = {}, showSelectAll = true)
    }
}
