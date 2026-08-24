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
 * A top-level tab: `:app`'s chrome floats over it, so this screen consumes `LocalAppChromePadding`
 * — the top half on the field, which never scrolls, the bottom half on the results list.
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

@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The chrome's TOP padding goes here rather than on the results list: the field never scrolls,
    // so it is the one thing that would sit permanently under the top nav. The BOTTOM half stays
    // with the list. Handed to `Modifier.padding` as an object — see [ChromeAwarePadding].
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
 * Polite, not assertive: it arrives while the user is typing, and interrupting each keystroke's
 * echo with a running total is worse than saying nothing. A real line of text rather than an
 * invisible announcer, because a zero-sized node is one TalkBack can never come back to.
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
 * Swapping one full-screen body for another says nothing at all, and the user is still in the field
 * above with no reason to go looking.
 *
 * @param merge folds the view into one node. Pass `false` for a view carrying an action: merging
 *   would take the button's own stop with it.
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

    // Keyed on `Unit`, so it fires once per *composition*: the NavHost disposes this screen when
    // another tab is on top and composes it fresh on return, but a keystroke does not remount it.
    // The blank-query guard means focus is only grabbed when there is nothing to disturb — returning
    // with results showing must not yank the keyboard up over someone scrolling.
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
        // The placeholder is the only thing naming this field on screen and vanishes once there is a
        // query, so the node carries the same words itself. No caption: the name is spoken, not drawn.
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
        // Only the bottom half of the chrome padding: the top half is on the outer column, and taking
        // it twice would push the first section a whole nav bar below the field.
        contentPadding = listContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        // MediaRow renders nothing for an empty list, but an empty `item` would still consume the
        // column's `spacedBy` gap, so empty sections are skipped here.
        videoSections(state = state, onItemClick = onItemClick)
        musicSections(state = state, onItemClick = onItemClick)
    }
}

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

/** A titled column rather than a `MediaRow`: a song has no artwork of its own worth a card. */
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

@Composable
private fun chromeTopPadding(): PaddingValues {
    val chrome = LocalAppChromePadding.current
    return remember(chrome) { ChromeAwarePadding(chrome = chrome, takeChromeTop = true) }
}

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
 * `:feature:search` cannot reuse `:feature:music`'s `TrackRow` — features never depend on each
 * other — so this is a small local equivalent.
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
