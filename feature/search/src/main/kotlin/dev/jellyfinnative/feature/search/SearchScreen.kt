package dev.jellyfinnative.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.ui.component.EmptyState
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.component.MediaRow
import dev.jellyfinnative.core.ui.component.PosterCard
import dev.jellyfinnative.core.ui.component.ThumbCard
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme

/**
 * The search screen: a debounced text field over one server query, rendered as one section per
 * item type (docs/PLAN.md, "Screens" → Search).
 *
 * Like the other top-level tabs it draws no bar of its own and no status-bar padding: `:app`'s
 * combined `AppTopBar` sits above it and the modifier it is handed already accounts for the bar
 * (which is what stopped the search field from rendering under the status-bar icons — the screen
 * used to be a bare full-screen `Column` under an edge-to-edge window).
 *
 * The [SearchViewModel] is passed in rather than resolved here so `:app` owns the
 * `hiltViewModel()` call together with the rest of the navigation graph wiring, as it does for
 * home.
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
    Column(modifier = modifier.fillMaxSize()) {
        SearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
        )

        when {
            state.error != null ->
                ErrorState(message = state.error.toMessage(), onRetry = onRetry)

            state.isSearching && state.hasNoResults -> LoadingState()

            state.query.isBlank() ->
                EmptyState(
                    message = stringResource(R.string.search_prompt),
                    icon = Icons.Filled.Search,
                )

            state.hasSearched && state.hasNoResults ->
                EmptyState(
                    message = stringResource(R.string.search_no_results, state.submittedQuery),
                    icon = Icons.Outlined.SearchOff,
                )

            else -> SearchResults(state = state, onItemClick = onItemClick)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(text = stringResource(R.string.search_field_label)) },
        singleLine = true,
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
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
    )
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    onItemClick: (JellyfinItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        // Sections in jellyfin-web's order; MediaRow renders nothing for an empty list, and an
        // empty `item` would still consume the column's `spacedBy` gap, so they are skipped here.
        //
        // The `contentType`s let the two poster sections reuse each other's nodes as results come
        // and go while the user types, instead of composing a row from scratch each time.
        if (state.movies.isNotEmpty()) {
            item(key = SECTION_MOVIES, contentType = ROW_POSTERS) {
                MediaRow(
                    title = stringResource(R.string.search_section_movies),
                    items = state.movies,
                    key = JellyfinItem::id,
                    contentType = CARD_POSTER,
                ) { item -> PosterCard(item = item, onClick = { onItemClick(item) }) }
            }
        }

        if (state.series.isNotEmpty()) {
            item(key = SECTION_SERIES, contentType = ROW_POSTERS) {
                MediaRow(
                    title = stringResource(R.string.search_section_series),
                    items = state.series,
                    key = JellyfinItem::id,
                    contentType = CARD_POSTER,
                ) { item -> PosterCard(item = item, onClick = { onItemClick(item) }) }
            }
        }

        if (state.episodes.isNotEmpty()) {
            item(key = SECTION_EPISODES, contentType = ROW_THUMBS) {
                MediaRow(
                    title = stringResource(R.string.search_section_episodes),
                    items = state.episodes,
                    key = JellyfinItem::id,
                    contentType = CARD_THUMB,
                ) { item -> ThumbCard(item = item, onClick = { onItemClick(item) }) }
            }
        }
    }
}

private const val SECTION_MOVIES = "section-movies"
private const val SECTION_SERIES = "section-series"
private const val SECTION_EPISODES = "section-episodes"

// Content types: rows of the same shape are interchangeable nodes, whatever section they belong to.
private const val ROW_POSTERS = "row-posters"
private const val ROW_THUMBS = "row-thumbs"
private const val CARD_POSTER = "card-poster"
private const val CARD_THUMB = "card-thumb"

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
