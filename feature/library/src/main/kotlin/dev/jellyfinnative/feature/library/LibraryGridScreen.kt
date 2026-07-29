package dev.jellyfinnative.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.ui.component.EmptyState
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.component.PosterCard
import dev.jellyfinnative.core.ui.theme.Dimens

/**
 * The library grid: every title in one library, paged, sortable and filterable
 * (docs/PLAN.md, "Screens" → LibraryGrid).
 *
 * Unlike the home screen, this one owns its `Scaffold`: the top bar carries the sort menu and the
 * filter action, which are the screen's own state and have no business in `:app`'s navigation
 * wiring.
 *
 * @param viewModel passed in rather than resolved here so `:app` owns the `hiltViewModel()` call,
 *   as it does for home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryGridScreen(
    viewModel: LibraryViewModel,
    onItemClick: (JellyfinItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.libraryName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.library_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(R.string.library_sort),
                        )
                    }
                    LibrarySortMenu(
                        expanded = sortMenuExpanded,
                        sortBy = state.sortBy,
                        sortOrder = state.sortOrder,
                        onDismiss = { sortMenuExpanded = false },
                        onSelectSort = viewModel::selectSort,
                        onToggleOrder = viewModel::toggleSortOrder,
                    )
                    FilterAction(
                        activeCount = state.activeFilterCount,
                        onClick = viewModel::openFilterSheet,
                    )
                },
            )
        },
    ) { innerPadding ->
        LibraryGridContent(
            items = items,
            hasActiveFilters = state.activeFilterCount > 0,
            onItemClick = onItemClick,
            onClearFilters = viewModel::clearFilters,
            modifier = Modifier.padding(innerPadding),
        )
    }

    if (state.isFilterSheetOpen) {
        LibraryFilterSheet(
            state = state,
            onDismiss = viewModel::dismissFilterSheet,
            onDraftChange = viewModel::updateDraftFilters,
            onApply = viewModel::applyFilters,
            onClear = viewModel::clearFilters,
            onRetryFacets = viewModel::retryFacets,
        )
    }
}

/**
 * The grid itself, with the three states Paging distinguishes: first-page loading, first-page
 * failure, and loaded (possibly empty).
 *
 * Appending a page has its own, non-blocking states at the bottom of the grid — a failed *second*
 * page must never replace the pages the user is already looking at with a full-screen error.
 */
@Composable
internal fun LibraryGridContent(
    items: LazyPagingItems<JellyfinItem>,
    hasActiveFilters: Boolean,
    onItemClick: (JellyfinItem) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshState = items.loadState.refresh

    when {
        refreshState is LoadState.Loading && items.itemCount == 0 -> LoadingState(modifier = modifier)

        refreshState is LoadState.Error ->
            ErrorState(
                message = refreshState.error.toPagingMessage(),
                modifier = modifier,
                onRetry = items::retry,
            )

        items.itemCount == 0 && refreshState is LoadState.NotLoading ->
            EmptyState(
                message =
                    if (hasActiveFilters) {
                        stringResource(R.string.library_empty_filtered)
                    } else {
                        stringResource(R.string.library_empty)
                    },
                modifier = modifier,
                actionLabel =
                    stringResource(R.string.library_empty_clear_filters).takeIf { hasActiveFilters },
                onAction = onClearFilters.takeIf { hasActiveFilters },
            )

        else -> ItemGrid(items = items, onItemClick = onItemClick, modifier = modifier)
    }
}

@Composable
private fun ItemGrid(
    items: LazyPagingItems<JellyfinItem>,
    onItemClick: (JellyfinItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        // The plan's grid metric: as many 110dp columns as the window fits, so a phone shows three
        // and the tablet in landscape shows six or more without a separate layout.
        columns = GridCells.Adaptive(MIN_CELL_WIDTH),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
            // Every cell is the same kind of node, so the grid can reuse one that scrolled off
            // instead of composing a new one — the single cheapest thing a lazy layout can be told.
            contentType = items.itemContentType { POSTER_CELL_CONTENT_TYPE },
        ) { index ->
            val item = items[index]
            if (item != null) {
                // `Dp.Unspecified` makes the card fill its column instead of taking a fixed width,
                // which is what a `GridCells.Adaptive` cell needs. It replaces a per-cell
                // `BoxWithConstraints`, i.e. one subcomposition per poster on every scroll.
                PosterCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    width = Dp.Unspecified,
                )
            }
        }

        appendState(items)
    }
}

/** The append (next-page) indicator pinned across the full width under the last row. */
private fun LazyGridScope.appendState(items: LazyPagingItems<JellyfinItem>) {
    when (val append = items.loadState.append) {
        is LoadState.Loading ->
            item(key = APPEND_KEY, span = { GridItemSpan(maxLineSpan) }) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(Dimens.SpaceLarge),
                )
            }

        is LoadState.Error ->
            item(key = APPEND_KEY, span = { GridItemSpan(maxLineSpan) }) {
                AppendError(message = append.error.toPagingMessage(), onRetry = items::retry)
            }

        is LoadState.NotLoading -> Unit
    }
}

@Composable
private fun AppendError(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.library_retry))
        }
    }
}

@Composable
private fun FilterAction(
    activeCount: Int,
    onClick: () -> Unit,
) {
    BadgedBox(
        badge = {
            if (activeCount > 0) {
                Badge { Text(text = activeCount.toString()) }
            }
        },
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.library_filter),
            )
        }
    }
}

/** Minimum grid column width from docs/PLAN.md, "Screens" → LibraryGrid. */
private val MIN_CELL_WIDTH = 110.dp

private const val APPEND_KEY = "library-append-state"

private const val POSTER_CELL_CONTENT_TYPE = "poster-card"
