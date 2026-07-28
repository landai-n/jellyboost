package dev.jellyfinnative.feature.library

import androidx.annotation.StringRes
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
import dev.jellyfinnative.core.common.model.SortBy
import dev.jellyfinnative.core.common.model.SortOrder
import dev.jellyfinnative.core.ui.theme.Dimens

/**
 * The sort menu behind the top bar's sort action.
 *
 * Picking the key that is already active flips the direction, which is how jellyfin-web's sort
 * control behaves; the explicit direction row underneath makes that discoverable.
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
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
            DropdownMenuItem(
                text = { Text(text = stringResource(option.labelRes())) },
                onClick = {
                    onSelectSort(option)
                    onDismiss()
                },
                leadingIcon = {
                    if (option == sortBy) {
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
