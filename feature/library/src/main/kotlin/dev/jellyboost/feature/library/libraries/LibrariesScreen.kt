package dev.jellyboost.feature.library.libraries

import androidx.compose.foundation.layout.Arrangement
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(MIN_CELL_WIDTH),
        modifier = modifier.fillMaxSize(),
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

/**
 * Minimum grid column width.
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
 */
private val MIN_CELL_WIDTH = Dimens.ThumbWidth

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
