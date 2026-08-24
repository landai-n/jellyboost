package dev.jellyboost.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.common.selection.ItemSelection
import dev.jellyboost.core.common.selection.SelectionIntent
import dev.jellyboost.core.ui.component.ActionPillChip
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.JellyboostSnackbarHost
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.POSTER_CARD_CONTENT_TYPE
import dev.jellyboost.core.ui.component.PillChip
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.ScreenHeader
import dev.jellyboost.core.ui.component.ScreenHeaderTitle
import dev.jellyboost.core.ui.component.SelectionAppBar
import dev.jellyboost.core.ui.component.batchOutcomeText
import dev.jellyboost.core.ui.component.rememberOneShotSnackbar
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.screenGlow
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * A *pushed* destination: `:app`'s chrome is hidden and `LocalAppChromePadding` is zero, so this
 * screen handles its own system-bar insets. The `Scaffold` is here for the snackbar alone.
 */
@Suppress(
    // Screen-level wiring: every visible piece is already a named composable and what is left binds
    // them, including two `sortAction` slots whose point is that one control appears in one place or
    // the other.
    "LongMethod",
)
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
    // Only *whether* the mode is on is read in this scope. Reading the set itself here would
    // recompose the whole screen on every toggle; each cell derives its own flag instead.
    val isSelecting by remember(selectionState) { derivedStateOf { selectionState.value.isActive } }
    val items = viewModel.items.collectAsLazyPagingItems()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = viewModel::consumeMessage,
        ) { batchOutcomeText(it.action, it.outcome) }

    // Only intercepts while the mode is on, so Back and the system gesture pop as usual otherwise.
    BackHandler(enabled = isSelecting) { viewModel.onSelection(SelectionIntent.Clear) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { JellyboostSnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        // One `BoxWithConstraints` for the whole screen, not per cell (see [ItemGrid]).
        BoxWithConstraints(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val isWide = maxWidth >= WIDE_WIDTH

            // Height follows width because the brush radius does: a fixed height that fits a phone chops
            // the gradient mid-fade on a tablet and draws a hard seam.
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(GLOW_ASPECT).screenGlow())

            Column(modifier = Modifier.fillMaxSize()) {
                // The contextual bar *replaces* the header and chip row rather than stacking over them: sort
                // and filters re-query the grid, which must not happen with a selection open.
                if (isSelecting) {
                    SelectionOverlay(selection = selectionState, onIntent = viewModel::onSelection)
                } else {
                    LibraryHeader(
                        title = state.libraryName,
                        totalCount = state.totalCount,
                        isWide = isWide,
                        onBack = onBack,
                        onHome = onHome,
                        sortAction = {
                            // Compact only: one screen shows one sort affordance.
                            if (!isWide) {
                                SortIconAction(
                                    sortBy = state.sortBy,
                                    sortOrder = state.sortOrder,
                                    expanded = sortMenuExpanded,
                                    onExpandedChange = { sortMenuExpanded = it },
                                    onSelectSort = viewModel::selectSort,
                                    onToggleOrder = viewModel::toggleSortOrder,
                                )
                            }
                        },
                    )

                    LibraryFilterRow(
                        state = state,
                        onToggleChip = viewModel::toggleFilterChip,
                        onClearFilters = viewModel::clearFilters,
                        onOpenSheet = viewModel::openFilterSheet,
                        sortAction = {
                            if (isWide) {
                                SortLabelAction(
                                    sortBy = state.sortBy,
                                    sortOrder = state.sortOrder,
                                    expanded = sortMenuExpanded,
                                    onExpandedChange = { sortMenuExpanded = it },
                                    onSelectSort = viewModel::selectSort,
                                    onToggleOrder = viewModel::toggleSortOrder,
                                )
                            }
                        },
                    )
                }

                LibraryGridContent(
                    items = items,
                    hasActiveFilters = state.activeFilterCount > 0,
                    selection = selectionState,
                    onItemClick = onItemClick,
                    onSelection = viewModel::onSelection,
                    onClearFilters = viewModel::clearFilters,
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
 * @param totalCount absent rather than empty while unknown, so the title does not jump when it lands.
 */
@Composable
private fun LibraryHeader(
    title: String,
    totalCount: Int?,
    isWide: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    sortAction: @Composable () -> Unit,
) {
    ScreenHeader(
        onBack = onBack,
        onHome = onHome,
        contentPadding =
            PaddingValues(
                start = Dimens.HeaderPadding,
                end = Dimens.HeaderPadding,
                top = Dimens.HeaderPadding,
                bottom = Dimens.SpaceSmall,
            ),
        trailing = { sortAction() },
    ) {
        ScreenHeaderTitle(
            text = title,
            style = if (isWide) JellyfinTypeExtras.ScreenTitleLarge else JellyfinTypeExtras.ScreenTitle,
        )
        if (totalCount != null) {
            Text(
                text = pluralStringResource(CoreUiR.plurals.library_item_count, totalCount, totalCount),
                style = HeaderCountStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LibraryFilterRow(
    state: LibraryUiState,
    onToggleChip: (LibraryFilterChip) -> Unit,
    onClearFilters: () -> Unit,
    onOpenSheet: () -> Unit,
    sortAction: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Dimens.HeaderPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = CHIP_ALL_KEY) {
                // Stays a `PillChip` because "All" *has* a state — drawn solid while no filter is applied.
                // "Filters" below has none; it opens a sheet.
                PillChip(
                    text = stringResource(R.string.library_filter_all),
                    selected = state.filters.isEmpty,
                    onClick = onClearFilters,
                )
            }

            items(items = state.filterChips, key = { it.chipKey() }) { chip ->
                PillChip(
                    text = chip.label(),
                    selected = state.filters.isApplied(chip),
                    onClick = { onToggleChip(chip) },
                )
            }

            item(key = CHIP_SHEET_KEY) {
                ActionPillChip(
                    text = stringResource(R.string.library_filter_more),
                    onClick = onOpenSheet,
                )
            }
        }

        sortAction()
    }
}

/** A stable identity per chip, so the row does not rebuild every pill when one filter changes. */
private fun LibraryFilterChip.chipKey(): String =
    when (this) {
        LibraryFilterChip.Unwatched -> "chip-unwatched"
        LibraryFilterChip.Watched -> "chip-watched"
        is LibraryFilterChip.Genre -> "chip-genre-$name"
        is LibraryFilterChip.Year -> "chip-year-$value"
    }

@Composable
private fun LibraryFilterChip.label(): String =
    when (this) {
        LibraryFilterChip.Unwatched -> stringResource(R.string.library_filters_played_no)
        LibraryFilterChip.Watched -> stringResource(R.string.library_filters_played_yes)
        is LibraryFilterChip.Genre -> name
        is LibraryFilterChip.Year -> value.toString()
    }

@Composable
private fun SortIconAction(
    sortBy: SortBy,
    sortOrder: SortOrder,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectSort: (SortBy) -> Unit,
    onToggleOrder: () -> Unit,
) {
    Box {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.Sort,
            contentDescription = stringResource(R.string.library_sort),
            onClick = { onExpandedChange(true) },
        )
        LibrarySortMenu(
            expanded = expanded,
            sortBy = sortBy,
            sortOrder = sortOrder,
            onDismiss = { onExpandedChange(false) },
            onSelectSort = onSelectSort,
            onToggleOrder = onToggleOrder,
        )
    }
}

@Composable
private fun SortLabelAction(
    sortBy: SortBy,
    sortOrder: SortOrder,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectSort: (SortBy) -> Unit,
    onToggleOrder: () -> Unit,
) {
    Box {
        Row(
            modifier =
                Modifier
                    // Without the role this glyph-and-word would announce nothing; the compact version is a
                    // `GlassIconButton`, which says so on its own.
                    .clickable(role = Role.Button) { onExpandedChange(true) }
                    .padding(horizontal = Dimens.HeaderPadding, vertical = Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SortLabelGap),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.library_sort),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SortLabelIconSize),
            )
            Text(
                text = stringResource(sortBy.labelRes()),
                style = SortLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        LibrarySortMenu(
            expanded = expanded,
            sortBy = sortBy,
            sortOrder = sortOrder,
            onDismiss = { onExpandedChange(false) },
            onSelectSort = onSelectSort,
            onToggleOrder = onToggleOrder,
        )
    }
}

/** Its own composable so reading the *count* recomposes this and nothing else. */
@Composable
private fun SelectionOverlay(
    selection: State<ItemSelection>,
    onIntent: (SelectionIntent) -> Unit,
) {
    SelectionAppBar(count = selection.value.count, onIntent = onIntent)
}

/**
 * A failed *second* page must never replace the pages the user is already looking at with a
 * full-screen error, so appends have their own non-blocking states at the bottom of the grid.
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
                announce = LiveRegionMode.Assertive,
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
                // The filtered case earns it: the user changed a chip and the grid emptied.
                announce = LiveRegionMode.Polite,
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
 * @param selection the selection **as a `State`**, not as a value. A cell that read the set directly
 *   would subscribe its scope to it, so toggling one card would recompose every visible card;
 *   reading it inside a per-cell [derivedStateOf] means each cell subscribes to its own `Boolean`.
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
        // The minimum is pinned to [Dimens.PosterWidth] so a library cell is never narrower than a home
        // poster card: `GridCells.Adaptive` only ever grows cells above `minSize`.
        columns = GridCells.Adaptive(MIN_CELL_WIDTH),
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Dimens.HeaderPadding,
                end = Dimens.HeaderPadding,
                top = Dimens.SpaceExtraSmall,
                // This screen consumes no `Scaffold` insets, so the last row buys its own navigation-bar clearance.
                bottom = Dimens.HeaderPadding + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(GridRowGap),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
            contentType = items.itemContentType { POSTER_CARD_CONTENT_TYPE },
        ) { index ->
            val item = items[index]
            if (item != null) {
                val id = item.id
                // Keyed on the item id so it survives the cell being reused for a different index.
                val selected by
                    remember(selection, id) {
                        derivedStateOf {
                            val current = selection.value
                            if (current.isActive) id in current else null
                        }
                    }

                // `Dp.Unspecified` makes the card fill its adaptive column instead of taking a fixed width.
                PosterCard(
                    item = item,
                    onClick = {
                        if (selection.value.isActive) {
                            onSelection(SelectionIntent.Toggle(id))
                        } else {
                            onItemClick(item)
                        }
                    },
                    width = Dp.Unspecified,
                    onLongClick = { onSelection(SelectionIntent.Toggle(id)) },
                    selected = selected,
                    ratingBadge = item.communityRating,
                )
            }
        }

        appendState(items)
    }
}

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
            Text(text = stringResource(CoreUiR.string.state_retry))
        }
    }
}

/**
 * Anchored to [Dimens.PosterWidth] rather than a smaller literal, which let a tablet settle at cells
 * narrower than the same poster on home. Never below the artwork request width (128dp).
 */
private val MIN_CELL_WIDTH = Dimens.PosterWidth

private val GridRowGap = 20.dp

/** Previews only: the live screen sizes the glow by [GLOW_ASPECT]. */
private val GlowHeight = 320.dp

/** Width : height of the glow box; height ≈ 70% of width, past the brush's ~61%-of-width fade-out. */
private const val GLOW_ASPECT = 10f / 7f

private val WIDE_WIDTH = 600.dp

private val SortLabelIconSize = 16.dp

private val SortLabelGap = 6.dp

private val SortLabelStyle =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
    )

private val HeaderCountStyle =
    TextStyle(
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )

private const val APPEND_KEY = "library-append-state"

private const val CHIP_ALL_KEY = "chip-all"

private const val CHIP_SHEET_KEY = "chip-filters"

@Preview(name = "Library header", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun LibraryHeaderPreview() {
    JellyfinTheme {
        Box(modifier = Modifier.height(GlowHeight)) {
            Box(modifier = Modifier.fillMaxWidth().height(GlowHeight).screenGlow())
            Column {
                LibraryHeader(
                    title = "Movies",
                    totalCount = 412,
                    isWide = false,
                    onBack = {},
                    onHome = {},
                    sortAction = {
                        SortIconAction(
                            sortBy = SortBy.SORT_NAME,
                            sortOrder = SortOrder.ASCENDING,
                            expanded = false,
                            onExpandedChange = {},
                            onSelectSort = {},
                            onToggleOrder = {},
                        )
                    },
                )
                LibraryFilterRow(
                    state =
                        LibraryUiState(
                            libraryName = "Movies",
                            filters = FilterOptions(genres = listOf("Thriller"), isPlayed = false),
                        ),
                    onToggleChip = {},
                    onClearFilters = {},
                    onOpenSheet = {},
                    sortAction = {},
                )
            }
        }
    }
}

@Preview(name = "Library header (wide)", showBackground = true, backgroundColor = 0xFF101010, widthDp = 900)
@Composable
private fun LibraryHeaderWidePreview() {
    val state =
        LibraryUiState(
            libraryName = "Shows",
            sortBy = SortBy.DATE_CREATED,
            sortOrder = SortOrder.DESCENDING,
            filters = FilterOptions(years = listOf(2024)),
        )

    JellyfinTheme {
        Box(modifier = Modifier.width(900.dp).height(GlowHeight)) {
            Box(modifier = Modifier.fillMaxWidth().height(GlowHeight).screenGlow())
            Column {
                LibraryHeader(
                    title = state.libraryName,
                    totalCount = 96,
                    isWide = true,
                    onBack = {},
                    onHome = {},
                    sortAction = {},
                )
                LibraryFilterRow(
                    state = state,
                    onToggleChip = {},
                    onClearFilters = {},
                    onOpenSheet = {},
                    sortAction = {
                        SortLabelAction(
                            sortBy = state.sortBy,
                            sortOrder = state.sortOrder,
                            expanded = false,
                            onExpandedChange = {},
                            onSelectSort = {},
                            onToggleOrder = {},
                        )
                    },
                )
            }
        }
    }
}
