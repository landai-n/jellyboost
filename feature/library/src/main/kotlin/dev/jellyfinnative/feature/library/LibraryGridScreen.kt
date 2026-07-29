package dev.jellyfinnative.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.selection.ItemSelection
import dev.jellyfinnative.core.common.selection.SelectionIntent
import dev.jellyfinnative.core.ui.component.EmptyState
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.component.PosterCard
import dev.jellyfinnative.core.ui.component.SelectionAppBar
import dev.jellyfinnative.core.ui.component.batchOutcomeText
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
 * @param onBack pops one entry — the plain back affordance.
 * @param onHome leaves the whole pushed chain at once and lands on the Home tab; see
 *   `AppScaffold.navigateHome`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryGridScreen(
    viewModel: LibraryViewModel,
    onItemClick: (JellyfinItem) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectionState = viewModel.selection.collectAsStateWithLifecycle()
    // Only *whether* the mode is on is read in this scope — a derived flag that flips twice per
    // selection, at the start and at the end. Reading the set itself here would recompose the whole
    // screen (and re-create the grid's content lambda) on every single toggle; the count is read
    // inside the top-bar slot, which is its own recomposition scope, and each cell derives its own
    // flag from `selectionState`.
    val isSelecting by remember(selectionState) { derivedStateOf { selectionState.value.isActive } }
    val items = viewModel.items.collectAsLazyPagingItems()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.userMessage?.let { batchOutcomeText(it.action, it.outcome) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    // Only intercepts while the mode is on, so the plain Back affordance and the system gesture
    // keep popping the destination exactly as before at every other moment.
    BackHandler(enabled = isSelecting) { viewModel.onSelection(SelectionIntent.Clear) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            // The contextual bar *replaces* the screen's own bar rather than stacking over it:
            // sort and filter re-query the grid, which is precisely what must not happen with a
            // selection open (LibraryViewModel.dropSelection), and Back's slot becomes the close
            // affordance so the top-left corner keeps meaning "get out of here".
            if (isSelecting) {
                SelectionAppBar(count = selectionState.value.count, onIntent = viewModel::onSelection)
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = state.libraryName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        // Both navigation affordances live in this slot rather than in `actions`,
                        // which sort and filter already own: Home belongs *next to* Back, and a
                        // pushed destination shows no app-level tab bar to escape through.
                        Row {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.library_back),
                                )
                            }
                            IconButton(onClick = onHome) {
                                Icon(
                                    imageVector = Icons.Filled.Home,
                                    contentDescription = stringResource(R.string.library_home),
                                )
                            }
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
            }
        },
    ) { innerPadding ->
        LibraryGridContent(
            items = items,
            hasActiveFilters = state.activeFilterCount > 0,
            selection = selectionState,
            onItemClick = onItemClick,
            onSelection = viewModel::onSelection,
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
    selection: State<ItemSelection>,
    onItemClick: (JellyfinItem) -> Unit,
    onSelection: (SelectionIntent) -> Unit,
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

        else ->
            ItemGrid(
                items = items,
                selection = selection,
                onItemClick = onItemClick,
                onSelection = onSelection,
                modifier = modifier,
            )
    }
}

/**
 * @param selection the selection **as a `State`**, not as a value.
 *
 * That is the whole of this screen's selection-performance story. A cell that read the set directly
 * would subscribe its recomposition scope to it, so toggling one card would recompose every visible
 * card. Reading it inside a per-cell [derivedStateOf] instead means each cell subscribes to *its
 * own* `Boolean`: flipping one card invalidates one cell, and Paging appending a page or a download
 * badge changing invalidates none of them (the grid recently got `contentType` and lost its
 * per-cell `BoxWithConstraints` for the same reason — this keeps that work intact).
 */
@Composable
private fun ItemGrid(
    items: LazyPagingItems<JellyfinItem>,
    selection: State<ItemSelection>,
    onItemClick: (JellyfinItem) -> Unit,
    onSelection: (SelectionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        // As many columns as the window fits, so a phone shows three and the tablet in landscape
        // shows eight or more without a separate layout. The minimum is pinned to the home rows'
        // poster width (Dimens.PosterWidth) rather than a smaller plan constant: GridCells.Adaptive
        // always grows cells to at least fill the row evenly at >= minSize, so anchoring the floor
        // to the same token home uses guarantees a library cell is never narrower than a home
        // poster card in either orientation (see MIN_CELL_WIDTH kdoc for the arithmetic).
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
                val id = item.id
                // One derived flag per cell, keyed on the item's id so it survives the cell being
                // reused for a different index. `derivedStateOf` re-evaluates when the set changes
                // but only invalidates this cell when *this* item's membership actually flipped.
                val selected by
                    remember(selection, id) {
                        derivedStateOf {
                            val current = selection.value
                            if (current.isActive) id in current else null
                        }
                    }

                // `Dp.Unspecified` makes the card fill its column instead of taking a fixed width,
                // which is what a `GridCells.Adaptive` cell needs. It replaces a per-cell
                // `BoxWithConstraints`, i.e. one subcomposition per poster on every scroll.
                PosterCard(
                    item = item,
                    onClick = {
                        // A tap opens the item normally, and toggles it while the mode is on —
                        // the one rule that makes a selection survive a mis-tap.
                        if (selection.value.isActive) {
                            onSelection(SelectionIntent.Toggle(id))
                        } else {
                            onItemClick(item)
                        }
                    },
                    width = Dp.Unspecified,
                    onLongClick = { onSelection(SelectionIntent.Toggle(id)) },
                    selected = selected,
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

/**
 * Minimum grid column width.
 *
 * docs/PLAN.md, "Screens" → LibraryGrid specifies `Adaptive(110.dp)`, but 110dp lets the test tablet tablet (1600x2560 @ 2.25x) fit 9 landscape columns at a settled cell width of ~112dp — narrower
 * than the 120dp [Dimens.PosterWidth] the home rows use for the same 2:3 poster shape, which reads
 * as an inconsistency between the two screens. Anchoring the floor to [Dimens.PosterWidth] itself
 * keeps the same device at 5 portrait columns of ~126dp (unchanged) and 8 landscape columns of
 * ~128dp (was 9 of ~112dp) — both now at or above the home card width, and still under
 * `ArtworkRequestWidths.POSTER_DP` (128dp), so the artwork request size doesn't need to change.
 */
private val MIN_CELL_WIDTH = Dimens.PosterWidth

private const val APPEND_KEY = "library-append-state"

private const val POSTER_CELL_CONTENT_TYPE = "poster-card"
