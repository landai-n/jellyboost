package dev.jellyboost.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.HomeSectionType
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.LibraryCard
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

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
                message = stringResource(R.string.home_empty_message),
                modifier = modifier,
                actionLabel = stringResource(R.string.home_empty_refresh),
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
    // One `BoxWithConstraints` for the whole screen — not per row or per card — matching the
    // pattern `LibrariesGrid` uses for the same problem (see its comment): a single subcomposition
    // buys the phone-vs-tablet branch in `homeThumbCardWidth` instead of one per thumb/library card.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cardWidth = homeThumbCardWidth(maxWidth)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Dimens.SpaceLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
        ) {
            // The order and the presence of every row is the user's, read from the server (see
            // `HomeLayoutRepository`); each row itself is unchanged. Sections this app has no row for
            // — audio/book resume, live TV — are skipped here rather than dropped upstream, so that
            // hiding one in jellyfin-web still moves the rows around it correctly.
            //
            // Empty sections are skipped entirely rather than emitted as zero-height items, which
            // would still consume the column's `spacedBy` gap and leave a visible hole.
            //
            // Every row declares its `contentType` — both here (a screenful of rows is itself a lazy
            // list) and inside `MediaRow` — so scrolling reuses nodes instead of composing new ones.
            var librariesDrawn = false
            state.sections.forEach { section ->
                when (section) {
                    // Both spellings of *My Media* are the same row for us, so a layout containing
                    // both draws it once — two items under one key would crash the lazy list.
                    HomeSectionType.SMALL_LIBRARY_TILES, HomeSectionType.LIBRARY_BUTTONS ->
                        if (!librariesDrawn) {
                            librariesDrawn = true
                            librariesRow(state, onLibraryClick, cardWidth)
                        }

                    HomeSectionType.RESUME -> resumeRow(state, onItemClick, cardWidth)
                    HomeSectionType.NEXT_UP -> nextUpRow(state, onItemClick, cardWidth)
                    HomeSectionType.LATEST_MEDIA -> latestRows(state, onItemClick, onLibraryClick)

                    HomeSectionType.NONE,
                    HomeSectionType.ACTIVE_RECORDINGS,
                    HomeSectionType.RESUME_AUDIO,
                    HomeSectionType.RESUME_BOOK,
                    HomeSectionType.LIVE_TV,
                    -> Unit
                }
            }
        }
    }
}

private fun LazyListScope.librariesRow(
    state: HomeUiState,
    onLibraryClick: (LibraryView) -> Unit,
    cardWidth: Dp,
) {
    if (state.libraries.isEmpty()) return
    item(key = SECTION_MY_MEDIA, contentType = ROW_LIBRARIES) {
        MediaRow(
            title = stringResource(R.string.home_section_my_media),
            items = state.libraries,
            key = LibraryView::id,
            contentType = CARD_LIBRARY,
        ) { library ->
            LibraryCard(library = library, onClick = { onLibraryClick(library) }, width = cardWidth)
        }
    }
}

private fun LazyListScope.resumeRow(
    state: HomeUiState,
    onItemClick: (JellyfinItem) -> Unit,
    cardWidth: Dp,
) {
    if (state.resume.isEmpty()) return
    item(key = SECTION_RESUME, contentType = ROW_THUMBS) {
        MediaRow(
            title = stringResource(R.string.home_section_continue_watching),
            items = state.resume,
            key = JellyfinItem::id,
            contentType = CARD_THUMB,
        ) { item ->
            ThumbCard(item = item, onClick = { onItemClick(item) }, width = cardWidth)
        }
    }
}

private fun LazyListScope.nextUpRow(
    state: HomeUiState,
    onItemClick: (JellyfinItem) -> Unit,
    cardWidth: Dp,
) {
    if (state.nextUp.isEmpty()) return
    item(key = SECTION_NEXT_UP, contentType = ROW_THUMBS) {
        MediaRow(
            title = stringResource(R.string.home_section_next_up),
            items = state.nextUp,
            key = JellyfinItem::id,
            contentType = CARD_THUMB,
        ) { item ->
            ThumbCard(item = item, onClick = { onItemClick(item) }, width = cardWidth)
        }
    }
}

private fun LazyListScope.latestRows(
    state: HomeUiState,
    onItemClick: (JellyfinItem) -> Unit,
    onLibraryClick: (LibraryView) -> Unit,
) {
    items(
        items = state.latest,
        key = { it.library.id },
        contentType = { ROW_POSTERS },
    ) { section ->
        MediaRow(
            title = stringResource(R.string.home_section_latest, section.library.name),
            items = section.items,
            key = JellyfinItem::id,
            contentType = CARD_POSTER,
            onSeeAll = { onLibraryClick(section.library) },
        ) { item ->
            PosterCard(item = item, onClick = { onItemClick(item) })
        }
    }
}

/**
 * Viewport width below which [homeThumbCardWidth] switches to [COMPACT_THUMB_WIDTH].
 *
 * 600dp is the standard compact/medium width-class boundary — the same cutoff
 * `librariesMinCellWidth` in `LibrariesScreen.kt` uses — and comfortably below every width this
 * screen actually renders at on a tablet: the test tablet is 711dp in portrait and 1138dp in
 * landscape, so both stay on the [Dimens.ThumbWidth] (210dp) branch and the tablet render this
 * file was calibrated against is unchanged.
 */
private val COMPACT_MAX_WIDTH = 600.dp

/**
 * Fixed *Continue Watching* / *Next Up* / *My Media* card width once the viewport drops below
 * [COMPACT_MAX_WIDTH].
 *
 * At 360dp — the narrowest phone this app targets — [Dimens.ThumbWidth] (210dp) only fits 1.6
 * cards per row, which reads as zoomed-in next to jellyfin-web. 160dp is chosen so exactly two
 * full cards plus a peek of a third fit: `ScreenPadding` (16dp) + 160 + `SpaceMedium` gutter
 * (12dp) + 160 = 348dp, inside the 360dp viewport, leaving a 12dp sliver of the next card as the
 * scroll affordance a fixed-width `LazyRow` needs (unlike `LibrariesGrid`'s adaptive grid, these
 * rows don't reflow to fill leftover width).
 */
private val COMPACT_THUMB_WIDTH = 160.dp

/**
 * Fixed width for Home's thumb-shaped cards (*My Media*, *Continue Watching*, *Next Up*) at a
 * viewport of [maxWidth].
 *
 * Pulled out of the composable so it's a plain, unit-testable function of the measured width —
 * see [COMPACT_THUMB_WIDTH] for why the compact value was chosen and [COMPACT_MAX_WIDTH] for why
 * the cutoff sits where it does. `Latest ...` poster rows are untouched by this: they use
 * `PosterCard`'s own fixed [Dimens.PosterWidth] (120dp) at every width.
 */
internal fun homeThumbCardWidth(maxWidth: Dp): Dp =
    if (maxWidth < COMPACT_MAX_WIDTH) COMPACT_THUMB_WIDTH else Dimens.ThumbWidth

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

@Preview(name = "Home (phone)", showBackground = true, backgroundColor = 0xFF101010, widthDp = 360, heightDp = 800)
@Composable
private fun HomeContentPhonePreview() {
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
