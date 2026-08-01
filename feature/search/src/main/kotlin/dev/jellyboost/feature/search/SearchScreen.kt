package dev.jellyboost.feature.search

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
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
    Column(modifier = modifier.fillMaxSize().padding(top = LocalAppChromePadding.current.calculateTopPadding())) {
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
                .focusRequester(focusRequester)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
    )
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    onItemClick: (JellyfinItem) -> Unit,
) {
    // Only the bottom half of the chrome padding: the top half is already on the outer column, and
    // taking it twice would push the first section a whole nav bar below the field.
    val chromeBottom = LocalAppChromePadding.current.calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(top = Dimens.SpaceLarge, bottom = Dimens.SpaceLarge + chromeBottom),
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
