package dev.jellyfinnative.feature.library.libraries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.ui.component.EmptyState
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LibraryCard
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme
import dev.jellyfinnative.feature.library.R
import dev.jellyfinnative.feature.library.toMessage

/**
 * The Libraries tab: every movie/TV library the user has, as a browsable grid
 * (docs/PLAN.md, "Confirmed decisions" — bottom nav bar Home / Libraries / Search / Downloads).
 *
 * Like the other top-level tabs, this screen owns its own [Scaffold]/[TopAppBar] rather than
 * relying on `:app`'s `AppScaffold`, which is bottom-nav-only (mirrors `LibraryGridScreen`).
 *
 * @param viewModel passed in rather than resolved here so `:app` owns the `hiltViewModel()` call
 *   together with the rest of the navigation graph wiring, as it does for the other screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(
    viewModel: LibrariesViewModel,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.libraries_title)) })
        },
    ) { innerPadding ->
        LibrariesContent(
            state = state,
            onRetry = viewModel::refresh,
            onLibraryClick = onLibraryClick,
            modifier = Modifier.padding(innerPadding),
        )
    }
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
        items(items = libraries, key = LibraryView::id) { library ->
            // `LibraryCard` takes a fixed width; measuring the cell here is what makes the card
            // fill its column instead of overflowing a narrow one or leaving a gap in a wide one
            // (same trick `LibraryGridScreen`'s `ItemGrid` uses for `PosterCard`).
            BoxWithConstraints {
                LibraryCard(
                    library = library,
                    onClick = { onLibraryClick(library) },
                    width = maxWidth,
                )
            }
        }
    }
}

/** Minimum grid column width — landscape library tiles read best a little wider than posters. */
private val MIN_CELL_WIDTH = 160.dp

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
