package dev.jellyboost.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults

/**
 * The sort menu behind the top bar's sort action.
 *
 * Picking the key that is already active flips the direction, which is how jellyfin-web's sort
 * control behaves; the explicit direction row underneath makes that discoverable.
 *
 * Which key is active was drawn as a leading tick and nothing else — the icon carries no
 * description, so the menu announced six identically-shaped options with no way to tell which one
 * the grid is already sorted by (accessibility audit 2026-08-05, A11Y-13). Each option now carries
 * real `selected` semantics, which is the same fact the tick draws, said in the voice a screen
 * reader already has for it. The tick stays exactly as it was.
 */
@Composable
internal fun LibrarySortMenu(
    expanded: Boolean,
    sortBy: SortBy,
    sortOrder: SortOrder,
    onDismiss: () -> Unit,
    onSelectSort: (SortBy) -> Unit,
    onToggleOrder: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        border = BorderStroke(GlassDefaults.HairlineWidth, GlassDefaults.PanelHairline),
    ) {
        Text(
            text = stringResource(R.string.library_sort_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    horizontal = Dimens.SpaceLarge,
                    vertical = Dimens.SpaceSmall,
                ),
        )

        LIBRARY_SORT_OPTIONS.forEach { option ->
            val isActive = option == sortBy
            DropdownMenuItem(
                text = { Text(text = stringResource(option.labelRes())) },
                // On the item's own modifier rather than on the tick: the row's `clickable` merges
                // its descendants, so a state declared on the icon would sit under the node
                // TalkBack focuses instead of on it (the same reasoning `AppActions`' offline
                // switch records, from the other direction).
                modifier = Modifier.semantics { selected = isActive },
                onClick = {
                    onSelectSort(option)
                    onDismiss()
                },
                leadingIcon = {
                    if (isActive) {
                        Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                    }
                },
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(text = stringResource(sortOrder.labelRes())) },
            onClick = {
                onToggleOrder()
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    imageVector =
                        when (sortOrder) {
                            SortOrder.ASCENDING -> Icons.Filled.ArrowUpward
                            SortOrder.DESCENDING -> Icons.Filled.ArrowDownward
                        },
                    contentDescription = stringResource(R.string.library_sort_direction),
                )
            },
        )
    }
}

/** Menu label for a sort key. */
@StringRes
internal fun SortBy.labelRes(): Int =
    when (this) {
        SortBy.SORT_NAME -> R.string.library_sort_name
        SortBy.DATE_CREATED -> R.string.library_sort_date_added
        SortBy.PREMIERE_DATE -> R.string.library_sort_premiere_date
        SortBy.COMMUNITY_RATING -> R.string.library_sort_community_rating
        SortBy.RUNTIME -> R.string.library_sort_runtime
        SortBy.RANDOM -> R.string.library_sort_random
    }

/** Menu label for a sort direction. */
@StringRes
internal fun SortOrder.labelRes(): Int =
    when (this) {
        SortOrder.ASCENDING -> R.string.library_sort_ascending
        SortOrder.DESCENDING -> R.string.library_sort_descending
    }
