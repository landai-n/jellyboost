package dev.jellyboost.feature.library.libraries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.LIBRARY_CARD_CONTENT_TYPE
import dev.jellyboost.core.ui.component.LibraryCard
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.feature.library.R
import dev.jellyboost.feature.library.toMessage
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * A top-level destination: `:app`'s chrome carries the navigation and floats *over* this grid, so
 * the grid consumes `LocalAppChromePadding` in its `contentPadding` — see [LibrariesGrid].
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
            ErrorState(
                message = state.error.toMessage(),
                modifier = modifier,
                onRetry = onRetry,
                announce = LiveRegionMode.Assertive,
            )

        state.isEmpty ->
            EmptyState(
                message = stringResource(R.string.libraries_empty),
                modifier = modifier,
                announce = LiveRegionMode.Polite,
            )

        else -> LibrariesGrid(libraries = state.libraries, onLibraryClick = onLibraryClick, modifier = modifier)
    }
}

@Composable
private fun LibrariesGrid(
    libraries: List<LibraryView>,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One `BoxWithConstraints` for the whole screen, not per cell: the cells deliberately do not
    // subcompose.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The chrome floats over this grid, so the first and last rows buy their own clearance here.
        val chrome = LocalAppChromePadding.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(librariesMinCellWidth(maxWidth)),
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    top = Dimens.ScreenPadding + chrome.calculateTopPadding(),
                    bottom = Dimens.ScreenPadding + chrome.calculateBottomPadding(),
                ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
        ) {
            // The title rides in the grid so it scrolls away with the first row instead of pinning a
            // band of chrome over a screen already wearing the app's own.
            item(key = TITLE_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.libraries_title),
                    style =
                        if (maxWidth >= COMPACT_MAX_WIDTH) {
                            JellyfinTypeExtras.ScreenTitleLarge
                        } else {
                            JellyfinTypeExtras.ScreenTitle
                        },
                    color = MaterialTheme.colorScheme.onBackground,
                    // It rides in the grid rather than a bar, so it is the only thing saying which screen this is.
                    modifier =
                        Modifier.padding(bottom = Dimens.SpaceExtraSmall).semantics { heading() },
                )
            }

            items(
                items = libraries,
                key = LibraryView::id,
                contentType = { LIBRARY_CARD_CONTENT_TYPE },
            ) { library ->
                // `Dp.Unspecified` makes the card fill its adaptive column instead of taking a fixed width.
                LibraryCard(
                    library = library,
                    onClick = { onLibraryClick(library) },
                    width = Dp.Unspecified,
                    // Null for a library served from the offline cache, which stores no count.
                    subtitle =
                        library.itemCount?.let { count ->
                            pluralStringResource(CoreUiR.plurals.library_item_count, count, count)
                        },
                )
            }
        }
    }
}

/**
 * Anchored to [Dimens.ThumbWidth] so this grid and Home's library row draw one card shape;
 * `Adaptive` only ever grows cells above the floor. Too tall for a phone, hence the compact branch.
 */
private val MIN_CELL_WIDTH = Dimens.ThumbWidth

/**
 * The largest floor that still gives a 360dp phone (328dp available) two columns: `Adaptive` needs
 * `2 * minSize + gutter <= available`, i.e. 2 * 150 + 12 = 312. Still above [Dimens.PosterWidth],
 * since this is a wide tile and not a poster.
 */
private val COMPACT_MIN_CELL_WIDTH = 150.dp

/** The standard compact/medium width-class boundary; every tablet width stays on [MIN_CELL_WIDTH]. */
private val COMPACT_MAX_WIDTH = 600.dp

internal fun librariesMinCellWidth(maxWidth: Dp): Dp =
    if (maxWidth < COMPACT_MAX_WIDTH) COMPACT_MIN_CELL_WIDTH else MIN_CELL_WIDTH

private const val TITLE_ITEM_KEY = "libraries-title"

@Preview(name = "Libraries", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 800)
@Composable
private fun LibrariesContentPreview() {
    val state =
        LibrariesUiState(
            isLoading = false,
            libraries =
                listOf(
                    LibraryView(
                        id = "lib-movies",
                        name = "Movies",
                        collectionType = CollectionKind.MOVIES,
                        itemCount = 412,
                    ),
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
                    LibraryView(
                        id = "lib-movies",
                        name = "Movies",
                        collectionType = CollectionKind.MOVIES,
                        itemCount = 412,
                    ),
                    LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS),
                ),
        )

    JellyfinTheme {
        LibrariesContent(state = state, onRetry = {}, onLibraryClick = {})
    }
}
