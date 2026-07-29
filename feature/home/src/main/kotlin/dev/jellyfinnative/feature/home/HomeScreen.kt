package dev.jellyfinnative.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.core.ui.component.EmptyState
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LibraryCard
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.component.MediaRow
import dev.jellyfinnative.core.ui.component.PosterCard
import dev.jellyfinnative.core.ui.component.ThumbCard
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme

/**
 * The home screen: the app's landing destination, mirroring jellyfin-web's row order so a
 * side-by-side comparison shows the same sections, items and ordering (the M2 definition of done).
 *
 * The [HomeViewModel] is passed in rather than resolved here so that `:app` owns the
 * `hiltViewModel()` call together with the rest of the navigation graph wiring — see
 * `HomeRoute` in `:app`.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onItemClick: (JellyfinItem) -> Unit,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onRetry = viewModel::refresh,
        onItemClick = onItemClick,
        onLibraryClick = onLibraryClick,
        modifier = modifier,
    )
}

/**
 * Stateless home rendering — everything the screen draws is a pure function of [state], which
 * keeps it previewable and testable without a ViewModel.
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState(modifier = modifier)

        state.errorMessage != null ->
            ErrorState(message = state.errorMessage, modifier = modifier, onRetry = onRetry)

        state.isEmpty ->
            EmptyState(
                message = "Nothing to watch yet. Add media to your libraries on the server.",
                modifier = modifier,
                actionLabel = "Refresh",
                onAction = onRetry,
            )

        else ->
            HomeRows(
                state = state,
                onItemClick = onItemClick,
                onLibraryClick = onLibraryClick,
                modifier = modifier,
            )
    }
}

@Composable
private fun HomeRows(
    state: HomeUiState,
    onItemClick: (JellyfinItem) -> Unit,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        // Empty sections are skipped entirely rather than emitted as zero-height items, which
        // would still consume the column's `spacedBy` gap and leave a visible hole.
        //
        // Every row declares its `contentType` — both here (a screenful of rows is itself a lazy
        // list) and inside `MediaRow` — so scrolling reuses nodes instead of composing new ones.
        if (state.libraries.isNotEmpty()) {
            item(key = SECTION_MY_MEDIA, contentType = ROW_LIBRARIES) {
                MediaRow(
                    title = "My Media",
                    items = state.libraries,
                    key = LibraryView::id,
                    contentType = CARD_LIBRARY,
                ) { library ->
                    LibraryCard(library = library, onClick = { onLibraryClick(library) })
                }
            }
        }

        if (state.resume.isNotEmpty()) {
            item(key = SECTION_RESUME, contentType = ROW_THUMBS) {
                MediaRow(
                    title = "Continue Watching",
                    items = state.resume,
                    key = JellyfinItem::id,
                    contentType = CARD_THUMB,
                ) { item ->
                    ThumbCard(item = item, onClick = { onItemClick(item) })
                }
            }
        }

        if (state.nextUp.isNotEmpty()) {
            item(key = SECTION_NEXT_UP, contentType = ROW_THUMBS) {
                MediaRow(
                    title = "Next Up",
                    items = state.nextUp,
                    key = JellyfinItem::id,
                    contentType = CARD_THUMB,
                ) { item ->
                    ThumbCard(item = item, onClick = { onItemClick(item) })
                }
            }
        }

        items(
            items = state.latest,
            key = { it.library.id },
            contentType = { ROW_POSTERS },
        ) { section ->
            MediaRow(
                title = "Latest ${section.library.name}",
                items = section.items,
                key = JellyfinItem::id,
                contentType = CARD_POSTER,
                onSeeAll = { onLibraryClick(section.library) },
            ) { item ->
                PosterCard(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

private const val SECTION_MY_MEDIA = "section-my-media"
private const val SECTION_RESUME = "section-resume"
private const val SECTION_NEXT_UP = "section-next-up"

// Content types: rows of the same shape are interchangeable nodes, whatever section they belong to.
private const val ROW_LIBRARIES = "row-libraries"
private const val ROW_THUMBS = "row-thumbs"
private const val ROW_POSTERS = "row-posters"
private const val CARD_LIBRARY = "card-library"
private const val CARD_THUMB = "card-thumb"
private const val CARD_POSTER = "card-poster"

@Preview(name = "Home", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 800)
@Composable
private fun HomeContentPreview() {
    val movies = LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES)
    val shows = LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS)
    val state =
        HomeUiState(
            isLoading = false,
            libraries = listOf(movies, shows),
            resume =
                listOf(
                    JellyfinItem(
                        id = "e1",
                        name = "The Bicameral Mind",
                        type = ItemType.EPISODE,
                        seriesName = "Westworld",
                        indexNumber = 10,
                        parentIndexNumber = 1,
                        runTimeTicks = 54_000_000_000L,
                        userData = UserData(playbackPositionTicks = 20_000_000_000L),
                    ),
                ),
            nextUp =
                listOf(
                    JellyfinItem(
                        id = "e2",
                        name = "Journey Into Night",
                        type = ItemType.EPISODE,
                        seriesName = "Westworld",
                        indexNumber = 1,
                        parentIndexNumber = 2,
                    ),
                ),
            latest =
                listOf(
                    LatestSection(
                        library = movies,
                        items =
                            listOf(
                                JellyfinItem(id = "m1", name = "Dune", type = ItemType.MOVIE, productionYear = 2021),
                                JellyfinItem(id = "m2", name = "Arrival", type = ItemType.MOVIE, productionYear = 2016),
                            ),
                    ),
                ),
        )

    JellyfinTheme {
        HomeContent(state = state, onRetry = {}, onItemClick = {}, onLibraryClick = {})
    }
}
