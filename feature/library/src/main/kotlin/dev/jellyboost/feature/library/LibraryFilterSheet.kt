package dev.jellyboost.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.PillChip
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/** Edits go into a *draft*: a chip tap must not re-query a 500-item library over the network. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryFilterSheet(
    state: LibraryUiState,
    onDismiss: () -> Unit,
    onDraftChange: (FilterOptions) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onRetryFacets: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        when {
            state.facetsError != null ->
                ErrorState(message = state.facetsError.toMessage(), onRetry = onRetryFacets)

            state.areFacetsLoading ->
                Text(
                    text = stringResource(R.string.library_filters_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Dimens.ScreenPadding),
                )

            else ->
                FilterSheetContent(
                    facets = state.facets,
                    draft = state.draftFilters,
                    onDraftChange = onDraftChange,
                    onApply = onApply,
                    onClear = onClear,
                )
        }
    }
}

@Composable
private fun FilterSheetContent(
    facets: FilterFacets,
    draft: FilterOptions,
    onDraftChange: (FilterOptions) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(bottom = Dimens.SpaceExtraLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        // Headings: a sheet of a hundred genre chips is what a heading-jump exists to get past.
        Text(
            text = stringResource(R.string.library_filters_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )

        PlayedFilterSection(draft = draft, onDraftChange = onDraftChange)

        if (facets.genres.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.library_filters_genres)) {
                facets.genres.forEach { genre ->
                    ToggleChip(
                        label = genre,
                        selected = genre in draft.genres,
                        onClick = { onDraftChange(draft.copy(genres = draft.genres.toggle(genre))) },
                    )
                }
            }
        }

        if (facets.years.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.library_filters_years)) {
                facets.years.forEach { year ->
                    ToggleChip(
                        label = year.toString(),
                        selected = year in draft.years,
                        onClick = { onDraftChange(draft.copy(years = draft.years.toggle(year))) },
                    )
                }
            }
        }

        if (facets.isEmpty) {
            Text(
                text = stringResource(R.string.library_filters_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostPillButton(text = stringResource(R.string.library_filters_clear), onClick = onClear, small = true)
            Spacer(modifier = Modifier.width(Dimens.SpaceSmall))
            PrimaryPillButton(text = stringResource(R.string.library_filters_apply), onClick = onApply, small = true)
        }
    }
}

/**
 * Not derived from the library's contents, unlike genres and years, so it is always drawn and its
 * three chips are spelled out here.
 */
@Composable
private fun PlayedFilterSection(
    draft: FilterOptions,
    onDraftChange: (FilterOptions) -> Unit,
) {
    FilterSection(title = stringResource(R.string.library_filters_played)) {
        ToggleChip(
            label = stringResource(R.string.library_filters_played_any),
            selected = draft.isPlayed == null,
            onClick = { onDraftChange(draft.copy(isPlayed = null)) },
        )
        ToggleChip(
            label = stringResource(R.string.library_filters_played_yes),
            selected = draft.isPlayed == true,
            onClick = { onDraftChange(draft.copy(isPlayed = true)) },
        )
        ToggleChip(
            label = stringResource(R.string.library_filters_played_no),
            selected = draft.isPlayed == false,
            onClick = { onDraftChange(draft.copy(isPlayed = false)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    chips: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        ) {
            chips()
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PillChip(text = label, selected = selected, onClick = onClick)
}

internal fun <T> List<T>.toggle(value: T): List<T> = if (value in this) this - value else this + value

@Preview(name = "Filter sheet", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun FilterSheetContentPreview() {
    JellyfinTheme {
        FilterSheetContent(
            facets =
                FilterFacets(
                    genres = listOf("Action", "Drama", "Science Fiction", "Thriller"),
                    years = listOf(2024, 2021, 2016, 1999),
                ),
            draft = FilterOptions(genres = listOf("Drama"), isPlayed = false),
            onDraftChange = {},
            onApply = {},
            onClear = {},
        )
    }
}
