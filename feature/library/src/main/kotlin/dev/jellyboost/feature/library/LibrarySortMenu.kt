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
 * Picking the key that is already active flips the direction, as jellyfin-web does. Each option
 * carries real `selected` semantics: the tick alone would leave six identically-shaped options with
 * nothing saying which is in force.
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
                // On the item's own modifier rather than the tick: the row's `clickable` merges descendants,
                // so a state declared on the icon would sit under the node TalkBack focuses instead of on it.
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

@StringRes
internal fun SortOrder.labelRes(): Int =
    when (this) {
        SortOrder.ASCENDING -> R.string.library_sort_ascending
        SortOrder.DESCENDING -> R.string.library_sort_descending
    }
