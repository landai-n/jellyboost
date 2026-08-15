package dev.jellyboost.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.AlbumCard
import dev.jellyboost.core.ui.component.ArtistCard
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.FieldLabel
import dev.jellyboost.core.ui.component.JellyfinTextField
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.component.POSTER_CARD_CONTENT_TYPE
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.THUMB_CARD_CONTENT_TYPE
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.theme.ChromeAwarePadding
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.LocalAppChromePadding

/**
 * The search screen: a debounced text field over one server query, rendered as one section per
 * item type (docs/PLAN.md, "Screens" → Search).
 *
 * Like the other top-level tabs it draws no bar of its own: `:app`'s chrome floats over it and
 * publishes how much of the window it covers through `LocalAppChromePadding`, which this screen
 * consumes so that neither the field nor the last result comes to rest under the glass (and so that
 * the field still clears the status bar, which an edge-to-edge window does not do on its own).
 *
 * The [SearchViewModel] is passed in rather than resolved here so `:app` owns the
 * `hiltViewModel()` call together with the rest of the navigation graph wiring, as it does for
 * home.
 *
 * The field autofocuses (and raises the keyboard) whenever the field is empty at entry — see the
 * `LaunchedEffect` in [SearchField] for why that fires on the right entries and not on every
 * keystroke.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onItemClick: (JellyfinItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::clearQuery,
        onRetry = viewModel::retry,
        onItemClick = onItemClick,
        modifier = modifier,
    )
}

/** Stateless search rendering — a pure function of [state], so it previews without a ViewModel. */
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The chrome's TOP padding goes on the outer column, not on the results list: the field is the
    // one thing on this screen that never scrolls, so it is also the one thing that would sit
    // permanently under the top nav (or under the compact layout's floating action cluster) if it
    // were left to the list's `contentPadding`. The BOTTOM half stays with the list — see
    // [SearchResults] — so results still scroll under the floating nav pill.
    //
    // Handed to `Modifier.padding` as an object rather than read here (audit 2026-08-08, PERF-20):
    // see [ChromeAwarePadding].
    Column(modifier = modifier.fillMaxSize().padding(chromeTopPadding())) {
        SearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
        )

        when {
            state.error != null ->
                Announced(message = state.error.toMessage(), merge = false) {
                    ErrorState(message = state.error.toMessage(), onRetry = onRetry)
                }

            state.isSearching && state.hasNoResults -> LoadingState()

            state.query.isBlank() ->
                EmptyState(
                    message = stringResource(R.string.search_prompt),
                    icon = Icons.Filled.Search,
                )

            state.hasSearched && state.hasNoResults ->
                Announced(message = stringResource(R.string.search_no_results, state.submittedQuery)) {
                    EmptyState(
                        message = stringResource(R.string.search_no_results, state.submittedQuery),
                        icon = Icons.Outlined.SearchOff,
                    )
                }

            else -> {
                ResultCountLine(count = state.resultCount)
                SearchResults(state = state, onItemClick = onItemClick)
            }
        }
    }
}

/**
 * How many things the search found, under the field.
 *
 * A polite live region, and the reason this composable exists: results appear *below* a field the
 * user is still typing in, so the one thing a search actually produces used to happen in complete
 * silence — no count, no "found something", nothing (accessibility audit 2026-08-05, A11Y-09). It
 * is a real line of visible text rather than an invisible announcer because a zero-sized node is a
 * node TalkBack can never come back to, and because the count is worth showing anyway: the sections
 * below are capped at [SearchViewModel.SEARCH_LIMIT] between them and each one only says its own
 * type.
 *
 * Polite, not assertive: it arrives while the user is typing, and interrupting each keystroke's
 * echo with a running total is worse than saying nothing.
 */
@Composable
private fun ResultCountLine(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.search_result_count, count, count),
        modifier =
            Modifier
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceExtraSmall)
                .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Wraps a state view in a polite live region, so replacing the results with it is announced.
 *
 * `:core:ui`'s `EmptyState`/`ErrorState` draw a screenful of content with no idea why they are on
 * screen; the announcement belongs to the caller that knows (here: a search that came back empty,
 * or a request that failed). Whether the message is *reached* by a screen reader was never the
 * problem — it is that swapping one full-screen body for another says nothing at all, and the user
 * is still in the field above with no reason to go looking.
 *
 * @param merge folds the whole state view into one node, which is right when it is only an icon and
 *   a sentence. Pass `false` for a view carrying an action: merging would take the button's own
 *   stop with it, and the description set here carries the words instead.
 */
@Composable
private fun Announced(
    message: String,
    merge: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics(mergeDescendants = merge) {
                    if (!merge) contentDescription = message
                    liveRegion = LiveRegionMode.Polite
                },
    ) {
        content()
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Land on the field with the keyboard already up, so the user can type immediately instead of
    // tapping first. Keyed on `Unit`, this fires once per *composition* of the screen rather than
    // once per keystroke: `:app`'s NavHost disposes `SearchScreen` when another top-level tab is on
    // top and composes it fresh on return (only the ViewModel/state survive that via
    // `restoreState` — see `topLevelNavOptions()` in AppScaffold.kt), so this effect re-runs on a
    // fresh entry and on every tab re-entry, but never on a plain recomposition (e.g. a keystroke
    // or results streaming in) since those don't remount the composable. Guarding on a blank query
    // means it only grabs focus when there is nothing to disturb: a first visit, or a tab
    // re-entry/return-from-detail with the field still empty. Re-entering (or returning from a
    // result's detail page) with results already showing leaves focus alone, so it doesn't yank the
    // keyboard back up over someone scrolling the list.
    LaunchedEffect(Unit) {
        if (query.isBlank()) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    JellyfinTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        singleLine = true,
        placeholder = { Text(text = stringResource(R.string.search_field_label)) },
        // The placeholder is the only thing naming this field on screen, and a placeholder vanishes
        // the moment there is a query — so the node carries the same words itself (audit CR-2).
        // No caption: the name is spoken, never drawn — the placeholder already draws it.
        label = FieldLabel(text = stringResource(R.string.search_field_label)),
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClearQuery) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_clear),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    onItemClick: (JellyfinItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Only the bottom half of the chrome padding: the top half is already on the outer column,
        // and taking it twice would push the first section a whole nav bar below the field. Read in
        // the layout phase rather than here — see [ChromeAwarePadding].
        contentPadding = listContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        // Sections in jellyfin-web's order; MediaRow renders nothing for an empty list, and an
        // empty `item` would still consume the column's `spacedBy` gap, so they are skipped here.
        //
        // The `contentType`s let the two poster sections reuse each other's nodes as results come
        // and go while the user types, instead of composing a row from scratch each time.
        //
        // `LazyListScope` extensions rather than composables, the `HomeScreen`/`DownloadsScreen`
        // convention: each one emits several `item`s, which only a lazy scope can do.
        videoSections(state = state, onItemClick = onItemClick)
        musicSections(state = state, onItemClick = onItemClick)
    }
}

/** Movies, series, episodes — the sections search had before M13. */
private fun LazyListScope.videoSections(
    state: SearchUiState,
    onItemClick: (JellyfinItem) -> Unit,
) {
    if (state.movies.isNotEmpty()) {
        item(key = SECTION_MOVIES, contentType = ROW_POSTERS) {
            MediaRow(
                title = stringResource(R.string.search_section_movies),
                items = state.movies,
                key = JellyfinItem::id,
                contentType = POSTER_CARD_CONTENT_TYPE,
            ) { item -> PosterCard(item = item, onClick = { onItemClick(item) }) }
        }
    }

    if (state.series.isNotEmpty()) {
        item(key = SECTION_SERIES, contentType = ROW_POSTERS) {
            MediaRow(
                title = stringResource(R.string.search_section_series),
                items = state.series,
                key = JellyfinItem::id,
                contentType = POSTER_CARD_CONTENT_TYPE,
            ) { item -> PosterCard(item = item, onClick = { onItemClick(item) }) }
        }
    }

    if (state.episodes.isNotEmpty()) {
        item(key = SECTION_EPISODES, contentType = ROW_THUMBS) {
            MediaRow(
                title = stringResource(R.string.search_section_episodes),
                items = state.episodes,
                key = JellyfinItem::id,
                contentType = THUMB_CARD_CONTENT_TYPE,
            ) { item -> ThumbCard(item = item, onClick = { onItemClick(item) }) }
        }
    }
}

/** M13 Phase 2 — music sections, in the same order the milestone plan lists them. */
private fun LazyListScope.musicSections(
    state: SearchUiState,
    onItemClick: (JellyfinItem) -> Unit,
) {
    if (state.artists.isNotEmpty()) {
        item(key = SECTION_ARTISTS, contentType = ROW_ARTISTS) {
            MediaRow(
                title = stringResource(R.string.search_section_artists),
                items = state.artists,
                key = JellyfinItem::id,
                contentType = CARD_ARTIST,
            ) { item -> ArtistCard(item = item, onClick = { onItemClick(item) }) }
        }
    }

    if (state.albums.isNotEmpty()) {
        item(key = SECTION_ALBUMS, contentType = ROW_ALBUMS) {
            MediaRow(
                title = stringResource(R.string.search_section_albums),
                items = state.albums,
                key = JellyfinItem::id,
                contentType = CARD_ALBUM,
            ) { item -> AlbumCard(item = item, onClick = { onItemClick(item) }) }
        }
    }

    if (state.songs.isNotEmpty()) {
        item(key = SECTION_SONGS, contentType = SECTION_SONGS) {
            SongSection(songs = state.songs, onItemClick = onItemClick)
        }
    }

    if (state.playlists.isNotEmpty()) {
        item(key = SECTION_PLAYLISTS, contentType = ROW_POSTERS) {
            MediaRow(
                title = stringResource(R.string.search_section_playlists),
                items = state.playlists,
                key = JellyfinItem::id,
                contentType = POSTER_CARD_CONTENT_TYPE,
            ) { item -> PosterCard(item = item, onClick = { onItemClick(item) }) }
        }
    }
}

/**
 * The songs section: a titled column of [SongRow]s rather than a `MediaRow`, since a song has no
 * artwork of its own worth a card.
 */
@Composable
private fun SongSection(
    songs: List<JellyfinItem>,
    onItemClick: (JellyfinItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.search_section_songs),
            style = JellyfinTypeExtras.SectionTitle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
        )
        songs.forEach { song -> SongRow(song = song, onClick = { onItemClick(song) }) }
    }
}

/** Just the chrome's top edge, resolved in the layout phase — see [ChromeAwarePadding]. */
@Composable
private fun chromeTopPadding(): PaddingValues {
    val chrome = LocalAppChromePadding.current
    return remember(chrome) { ChromeAwarePadding(chrome = chrome, takeChromeTop = true) }
}

/** The results list's padding: its own spacing, plus the chrome's bottom edge (deferred). */
@Composable
private fun listContentPadding(): PaddingValues {
    val chrome = LocalAppChromePadding.current
    return remember(chrome) {
        ChromeAwarePadding(
            chrome = chrome,
            top = Dimens.SpaceLarge,
            bottom = Dimens.SpaceLarge,
            takeChromeBottom = true,
        )
    }
}

/**
 * A song search result: a compact list row rather than a card. `:feature:search` cannot reuse
 * `:feature:music`'s `TrackRow` — features never depend on each other (docs/PLAN.md, "Project
 * skeleton") — so this is a small local equivalent, without the download badge / favourite affordances
 * a track's own screen offers.
 */
@Composable
private fun SongRow(
    song: JellyfinItem,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
    ) {
        Text(
            text = song.name,
            style = SongTitleStyle,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        song.displaySubtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = SongSubtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SongTitleStyle = TextStyle(fontSize = 14.sp)
private val SongSubtitleStyle = TextStyle(fontSize = 12.sp)

private const val SECTION_MOVIES = "section-movies"
private const val SECTION_SERIES = "section-series"
private const val SECTION_EPISODES = "section-episodes"
private const val SECTION_ARTISTS = "section-artists"
private const val SECTION_ALBUMS = "section-albums"
private const val SECTION_SONGS = "section-songs"
private const val SECTION_PLAYLISTS = "section-playlists"

// Content types: rows of the same shape are interchangeable nodes, whatever section they belong to.
// The card types (poster/thumb) come from `:core:ui`, beside the cards they describe (DUP-15).
private const val ROW_POSTERS = "row-posters"
private const val ROW_THUMBS = "row-thumbs"
private const val ROW_ARTISTS = "row-artists"
private const val ROW_ALBUMS = "row-albums"
private const val CARD_ARTIST = "card-artist"
private const val CARD_ALBUM = "card-album"

@Preview(name = "Search", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 800)
@Composable
private fun SearchContentPreview() {
    val state =
        SearchUiState(
            query = "west",
            submittedQuery = "west",
            hasSearched = true,
            movies =
                listOf(
                    JellyfinItem(id = "m1", name = "Westward", type = ItemType.MOVIE, productionYear = 2019),
                ),
            series =
                listOf(
                    JellyfinItem(id = "s1", name = "Westworld", type = ItemType.SERIES, productionYear = 2016),
                ),
            episodes =
                listOf(
                    JellyfinItem(
                        id = "e1",
                        name = "The Original",
                        type = ItemType.EPISODE,
                        seriesName = "Westworld",
                        indexNumber = 1,
                        parentIndexNumber = 1,
                    ),
                ),
        )

    JellyfinTheme {
        SearchContent(
            state = state,
            onQueryChange = {},
            onClearQuery = {},
            onRetry = {},
            onItemClick = {},
        )
    }
}
