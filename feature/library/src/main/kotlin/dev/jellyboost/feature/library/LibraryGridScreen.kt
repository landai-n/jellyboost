package dev.jellyboost.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import dev.jellyboost.core.ui.component.SelectionAppBar
import dev.jellyboost.core.ui.component.batchOutcomeText
import dev.jellyboost.core.ui.component.rememberOneShotSnackbar
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The library grid: every title in one library, paged, sortable and filterable
 * (docs/PLAN.md, "Screens" → LibraryGrid).
 *
 * Since the 2026 refresh the screen wears no `TopAppBar`: it opens on its own large-title header of
 * glass buttons over a faint accent glow, with the applied filters spelled out as chips underneath
 * rather than hidden behind a badge (spec 4b). It is a **pushed** destination, so `:app`'s chrome is
 * hidden and `LocalAppChromePadding` is zero — this screen handles its own system-bar insets, as it
 * always did.
 *
 * The `Scaffold` that remains is here for the snackbar alone, hence `contentWindowInsets =
 * WindowInsets(0)`: the header pads itself against the status bar and the grid scrolls under it.
 *
 * @param viewModel passed in rather than resolved here so `:app` owns the `hiltViewModel()` call,
 *   as it does for home.
 * @param onBack pops one entry — the plain back affordance.
 * @param onHome leaves the whole pushed chain at once and lands on the Home tab; see
 *   `AppScaffold.navigateHome`.
 */
@Suppress(
    // Screen-level wiring: every visible piece is already a named composable (`LibraryHeader`, `LibraryFilterRow`,
    // `LibraryGridContent`, `SelectionOverlay`) and what is left is the plumbing that binds them — including two
    // `sortAction` slots whose whole point is that the same control appears in one place or the other. A wrapper around
    // it would need ten parameters to say less.
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
    // Only *whether* the mode is on is read in this scope — a derived flag that flips twice per
    // selection, at the start and at the end. Reading the set itself here would recompose the whole
    // screen (and re-create the grid's content lambda) on every single toggle; the count is read
    // inside the selection bar's own slot, which is its own recomposition scope, and each cell
    // derives its own flag from `selectionState`.
    val isSelecting by remember(selectionState) { derivedStateOf { selectionState.value.isActive } }
    val items = viewModel.items.collectAsLazyPagingItems()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = viewModel::consumeMessage,
        ) { batchOutcomeText(it.action, it.outcome) }

    // Only intercepts while the mode is on, so the plain Back affordance and the system gesture
    // keep popping the destination exactly as before at every other moment.
    BackHandler(enabled = isSelecting) { viewModel.onSelection(SelectionIntent.Clear) }

    Scaffold(
        modifier = modifier,
        // The screen draws under the system bars: the header carries the status-bar inset and the
        // grid's own contentPadding clears the navigation bar.
        contentWindowInsets = WindowInsets(0),
        // A pushed destination, so `LocalAppChromePadding` is zero and the shared host's policy
        // resolves to the navigation-bar inset this screen used to apply by hand.
        snackbarHost = { JellyboostSnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        // One `BoxWithConstraints` for the whole screen — not per cell (see [ItemGrid]). It buys
        // the two places the layout differs on a wide window: the title's size, and whether sort is
        // an icon in the header or a labelled control at the end of the chip row.
        BoxWithConstraints(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val isWide = maxWidth >= WIDE_WIDTH

            // Behind everything, anchored to the top of the window: the screen has no artwork of
            // its own, and this is what keeps the header from reading as text on a black rectangle.
            Box(modifier = Modifier.fillMaxWidth().height(GlowHeight).background(JellyfinGradients.ScreenGlow))

            Column(modifier = Modifier.fillMaxSize()) {
                // The contextual bar *replaces* the header and the chip row rather than stacking
                // over them: sort and filters re-query the grid, which is precisely what must not
                // happen with a selection open (LibraryViewModel.dropSelection), and the close
                // affordance lands where Back was, so the top-left corner keeps meaning "get out of
                // here".
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
                            // Compact only: on a wide layout sort is the labelled control at the
                            // end of the chip row, and one screen shows one sort affordance.
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
 * The screen's header: navigation at the start, the library's name and size in the middle, sort at
 * the end.
 *
 * Both navigation affordances are here rather than one of them hiding in an overflow: a pushed
 * destination shows no tab bar to escape through, so Home is as much a way out as Back is — the
 * convention every pushed screen in the app follows (`ItemDetailScreen.OverlayNav`).
 *
 * @param totalCount how many items the grid holds, when known — see [LibraryUiState.totalCount].
 *   The line is absent rather than empty while it is not, so the title does not jump when it lands.
 * @param sortAction the sort affordance, passed in because *where* it belongs depends on the
 *   window width; empty on a wide layout, where the chip row hosts it instead.
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
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    start = HeaderPadding,
                    end = HeaderPadding,
                    top = HeaderPadding,
                    // The chip row sits one small gap under the title block, not a full 20dp: the
                    // two read as one header, and the grid's own padding follows below them.
                    bottom = Dimens.SpaceSmall,
                ),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.library_back),
            onClick = onBack,
        )
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.library_home),
            onClick = onHome,
        )

        Column(modifier = Modifier.weight(1f).padding(start = Dimens.SpaceExtraSmall)) {
            Text(
                text = title,
                style = if (isWide) JellyfinTypeExtras.ScreenTitleLarge else JellyfinTypeExtras.ScreenTitle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The screen's own heading — where a heading-jump lands, and the full library name
                // whatever the one ellipsized line had room for (audit A11Y-10).
                modifier =
                    Modifier.semantics {
                        heading()
                        contentDescription = title
                    },
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

        sortAction()
    }
}

/**
 * The inline filter row: what is applied, as chips, and the two ways to change it.
 *
 * *All* clears, each facet chip toggles the filter it names, and *Filters* opens the sheet that
 * still owns the full editor. The chips are shortcuts into the same
 * [dev.jellyboost.core.common.model.FilterOptions] the sheet edits — no chip means anything the
 * sheet cannot express — so the badge counting active filters is gone: they are legible instead.
 *
 * @param sortAction empty on a compact layout, where sort is an icon up in the header; on a wide
 *   one it hugs the end of this row as a labelled control, which is where the extra width is.
 */
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
            contentPadding = PaddingValues(horizontal = HeaderPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = CHIP_ALL_KEY) {
                // Stays a `PillChip` while its neighbour became an `ActionPillChip`: "All" *has* a
                // state — it is drawn solid while no filter is applied — and it behaves exactly
                // like the radio button in a group that its facets make it. "Filters" below has no
                // state to have; it opens a sheet.
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

/** The chip's copy — the sheet's own words for the same filters, never a second wording. */
@Composable
private fun LibraryFilterChip.label(): String =
    when (this) {
        LibraryFilterChip.Unwatched -> stringResource(R.string.library_filters_played_no)
        LibraryFilterChip.Watched -> stringResource(R.string.library_filters_played_yes)
        is LibraryFilterChip.Genre -> name
        is LibraryFilterChip.Year -> value.toString()
    }

/** Sort on a compact layout: a glass circle in the header, with the menu hanging off it. */
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
        // The one sort menu, wherever its anchor happens to be at this width.
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

/**
 * Sort on a wide layout: the current key spelled out at the end of the chip row.
 *
 * The same menu, opened from a label rather than a glyph — at this width the row has room to say
 * what the grid is sorted by instead of making the user open a menu to find out.
 */
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
                    // A glyph and a word that open a menu: the compact layout's version of this is
                    // a `GlassIconButton` and has always said so; this one said nothing (ROLE-01).
                    .clickable(role = Role.Button) { onExpandedChange(true) }
                    .padding(horizontal = HeaderPadding, vertical = Dimens.SpaceSmall),
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

/**
 * The contextual bar, in a composable of its own so that reading the *count* recomposes this and
 * nothing else — `LibraryGridScreen` only ever reads whether the mode is on.
 */
@Composable
private fun SelectionOverlay(
    selection: State<ItemSelection>,
    onIntent: (SelectionIntent) -> Unit,
) {
    SelectionAppBar(count = selection.value.count, onIntent = onIntent)
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
                // The filtered case is the one that earns it: the user changed a chip and the grid
                // emptied, which is a result and has to be reported like one.
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
        // As many columns as the window fits, so a phone shows two or three and the tablet in
        // landscape shows seven or more without a separate layout. The minimum is pinned to the
        // home rows' poster width (Dimens.PosterWidth) rather than a smaller plan constant:
        // GridCells.Adaptive always grows cells to at least fill the row evenly at >= minSize, so
        // anchoring the floor to the same token home uses guarantees a library cell is never
        // narrower than a home poster card in either orientation (see MIN_CELL_WIDTH kdoc).
        columns = GridCells.Adaptive(MIN_CELL_WIDTH),
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = HeaderPadding,
                end = HeaderPadding,
                top = Dimens.SpaceExtraSmall,
                // Nothing below the grid clears the navigation bar for it — this screen consumes no
                // `Scaffold` insets, so the last row buys its own clearance here, where it scrolls.
                bottom = HeaderPadding + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(GridRowGap),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
            // Every cell is the same kind of node, so the grid can reuse one that scrolled off
            // instead of composing a new one — the single cheapest thing a lazy layout can be told.
            contentType = items.itemContentType { POSTER_CARD_CONTENT_TYPE },
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
                    // The grid is where a rating is worth the pixels: it is the one screen whose
                    // whole job is choosing between titles you have not opened yet.
                    ratingBadge = item.communityRating,
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

/**
 * Minimum grid column width.
 *
 * docs/PLAN.md, "Screens" → LibraryGrid specifies `Adaptive(110.dp)`, which lets a tablet settle at
 * cells narrower than the [Dimens.PosterWidth] the home rows draw the same 2:3 poster at — an
 * inconsistency between the two screens. Anchoring the floor to [Dimens.PosterWidth] itself keeps
 * the test tablet (1600x2560 @ 2.25x) at 4 portrait columns of ~156dp and 7 landscape columns of
 * ~143dp with this screen's 20dp side padding and 16dp gutters, and a 360dp phone at 2 columns of
 * ~152dp — all comfortably at or above the home card width, and none of them narrower than the
 * artwork request (`ArtworkRequestWidths.POSTER_DP`, 128dp) actually fetches.
 */
private val MIN_CELL_WIDTH = Dimens.PosterWidth

/**
 * Side padding of the header, the chip row and the grid — the screen's own edge.
 *
 * 20dp rather than [Dimens.ScreenPadding]: the refresh's headers sit a touch wider than the
 * content-only screens, and the grid follows the header so the first poster lines up under the
 * title rather than 4dp inside it.
 */
private val HeaderPadding = 20.dp

/** Vertical gutter between grid rows — wider than the horizontal one, which the titles fill. */
private val GridRowGap = 20.dp

/**
 * How tall the accent glow behind the header is.
 *
 * The gradient's radius is derived from the *width* of the box it fills, so the box has to be at
 * least roughly as tall as the glow is wide or the fade is cut off at the bottom edge — a hard seam
 * across the background. 320dp clears that on every phone and covers the header plus the chip row.
 */
private val GlowHeight = 320.dp

/** Width at which the header grows its title and sort moves into the chip row as a label. */
private val WIDE_WIDTH = 600.dp

private val SortLabelIconSize = 16.dp

/** Gap between the sort glyph and its label. */
private val SortLabelGap = 6.dp

private val SortLabelStyle =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
    )

/** The count under the library's name — quiet, and a step smaller than a card title. */
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
            Box(modifier = Modifier.fillMaxWidth().height(GlowHeight).background(JellyfinGradients.ScreenGlow))
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
            Box(modifier = Modifier.fillMaxWidth().height(GlowHeight).background(JellyfinGradients.ScreenGlow))
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
