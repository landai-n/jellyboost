package dev.jellyboost.feature.library.libraries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.LibraryCard
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.feature.library.R
import dev.jellyboost.feature.library.toMessage

/**
 * The Libraries tab: every movie/TV library the user has, as a browsable grid
 * (docs/PLAN.md, "Confirmed decisions" — bottom nav bar Home / Libraries / Search / Downloads).
 *
 * It draws no bar of its own: `:app`'s combined `AppTopBar` carries the navigation and the app
 * actions for every top-level destination, and its selected tab already says "Libraries"
 * (DECISIONS.md 2026-07-29). Pushed screens such as `LibraryGridScreen` still own their bars,
 * because they have a back action and screen-specific actions to put in them.
 *
 * @param viewModel passed in rather than resolved here so `:app` owns the `hiltViewModel()` call
 *   together with the rest of the navigation graph wiring, as it does for the other screens.
 */
@Composable
fun LibrariesScreen(
    viewModel: LibrariesViewModel,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LibrariesContent(
        state = state,
        onRetry = viewModel::refresh,
        onLibraryClick = onLibraryClick,
        modifier = modifier,
    )
}

/** Stateless rendering — a pure function of [state], so it previews without a ViewModel. */
@Composable
internal fun LibrariesContent(
    state: LibrariesUiState,
    onRetry: () -> Unit,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState(modifier = modifier)

        state.error != null ->
            ErrorState(message = state.error.toMessage(), modifier = modifier, onRetry = onRetry)

        state.isEmpty -> EmptyState(message = stringResource(R.string.libraries_empty), modifier = modifier)

        else -> LibrariesGrid(libraries = state.libraries, onLibraryClick = onLibraryClick, modifier = modifier)
    }
}

@Composable
private fun LibrariesGrid(
    libraries: List<LibraryView>,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One `BoxWithConstraints` for the whole screen — not per cell. The grid items deliberately
    // avoid subcomposing per card (see the comment below); this is the single subcomposition that
    // buys the phone-vs-tablet branch in `librariesMinCellWidth` instead.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(librariesMinCellWidth(maxWidth)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Dimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
        ) {
            items(
                items = libraries,
                key = LibraryView::id,
                // One content type for every cell: the grid can then reuse a scrolled-off card's node
                // instead of building a new one (same reason `LibraryGridScreen`'s `ItemGrid` does it).
                contentType = { LIBRARY_CELL_CONTENT_TYPE },
            ) { library ->
                // `Dp.Unspecified` makes the card fill its column rather than take a fixed width, which
                // is what a `GridCells.Adaptive` cell needs — and it costs no subcomposition, unlike the
                // per-cell `BoxWithConstraints` this replaced.
                LibraryCard(
                    library = library,
                    onClick = { onLibraryClick(library) },
                    width = Dp.Unspecified,
                )
            }
        }
    }
}

/**
 * Minimum grid column width for tablet / regular-width screens (>= [COMPACT_MAX_WIDTH]).
 *
 * This screen has no spec in docs/PLAN.md (only `LibraryGrid` — the paged item grid *inside* a
 * library — does), so 160dp was a screen-local guess. It read smaller than Home's *My Media* row,
 * which draws the same 16:9 library-tile shape at a fixed [Dimens.ThumbWidth] (210dp): on the test tablet, `Adaptive(160.dp)` settles at 4 portrait columns of ~161dp and 6 landscape columns of
 * ~174dp — both below Home's 210dp card, and portrait in particular is barely off the 160dp floor.
 * Anchoring the floor to [Dimens.ThumbWidth] itself (the same token Home's row uses) gives 3
 * portrait columns of ~218dp and 5 landscape columns of ~212dp — both now at or above the home
 * card width (`Adaptive` always grows cells to ≥ minSize) — and the widest of those, ~218dp, still
 * sits under `ArtworkRequestWidths.THUMB_DP` (224dp), so the artwork request size doesn't need to
 * change.
 *
 * At phone widths this floor is too tall: a 360dp device has 328dp available after
 * [Dimens.ScreenPadding], and `Adaptive` only starts a second column once two 210dp cells plus the
 * 12dp gutter fit (432dp) — below that it folds to a single full-width column, which is the device
 * bug this file was fixed for. See [librariesMinCellWidth] for the compact-width branch.
 */
private val MIN_CELL_WIDTH = Dimens.ThumbWidth

/**
 * Minimum grid column width once the viewport drops below [COMPACT_MAX_WIDTH].
 *
 * 150dp is the largest floor that still gives a 360dp phone (328dp available) two columns:
 * `Adaptive` needs `2 * minSize + gutter <= available`, i.e. `2 * 150 + 12 = 312 <= 328`, so it
 * settles at 2 columns of ~158dp. It also stays comfortably above [Dimens.PosterWidth] (120dp) —
 * this is a 16:9 library tile, not a poster, and shrinking it to poster width would misread as a
 * different card type.
 */
private val COMPACT_MIN_CELL_WIDTH = 150.dp

/**
 * Viewport width below which [LibrariesGrid] switches to [COMPACT_MIN_CELL_WIDTH].
 *
 * 600dp is the standard compact/medium width-class boundary, and comfortably below every width
 * this screen actually renders at on a tablet: the test tablet is 711dp in portrait and 1138dp in
 * landscape, so both stay on the [MIN_CELL_WIDTH] (210dp) branch and the tablet render this file
 * was calibrated against is unchanged.
 */
private val COMPACT_MAX_WIDTH = 600.dp

/**
 * The `GridCells.Adaptive` floor for a [LibrariesGrid] whose viewport is [maxWidth] wide.
 *
 * Pulled out of the composable so it's a plain, unit-testable function of the measured width —
 * see [MIN_CELL_WIDTH] and [COMPACT_MIN_CELL_WIDTH] for why each branch's value was chosen, and
 * [COMPACT_MAX_WIDTH] for why the cutoff sits where it does.
 */
internal fun librariesMinCellWidth(maxWidth: Dp): Dp =
    if (maxWidth < COMPACT_MAX_WIDTH) COMPACT_MIN_CELL_WIDTH else MIN_CELL_WIDTH

private const val LIBRARY_CELL_CONTENT_TYPE = "library-card"

@Preview(name = "Libraries", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 800)
@Composable
private fun LibrariesContentPreview() {
    val state =
        LibrariesUiState(
            isLoading = false,
            libraries =
                listOf(
                    LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES),
                    LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS),
                ),
        )

    JellyfinTheme {
        LibrariesContent(state = state, onRetry = {}, onLibraryClick = {})
    }
}

@Preview(name = "Libraries (phone)", showBackground = true, backgroundColor = 0xFF101010, widthDp = 360, heightDp = 800)
@Composable
private fun LibrariesContentPhonePreview() {
    val state =
        LibrariesUiState(
            isLoading = false,
            libraries =
                listOf(
                    LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES),
                    LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS),
                ),
        )

    JellyfinTheme {
        LibrariesContent(state = state, onRetry = {}, onLibraryClick = {})
    }
}
