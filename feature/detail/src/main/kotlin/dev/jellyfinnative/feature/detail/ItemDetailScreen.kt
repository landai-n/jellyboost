package dev.jellyfinnative.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.ui.component.BackdropHeader
import dev.jellyfinnative.core.ui.component.EmptyState
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.component.MediaRow
import dev.jellyfinnative.core.ui.component.PosterCard
import dev.jellyfinnative.core.ui.component.ThumbCard
import dev.jellyfinnative.core.ui.theme.Dimens

/**
 * The movie / series / season detail screen (docs/PLAN.md, "Screens" → ItemDetail).
 *
 * Like `HomeScreen`, the ViewModel is passed in rather than resolved here so that `:app` owns the
 * `hiltViewModel()` call together with the rest of the navigation graph.
 *
 * @param onItemClick a season, episode or related item was tapped — the caller pushes another
 *   `Routes.ItemDetail` for it.
 * @param onPlay play was requested for an item, at the given position in Jellyfin ticks — the
 *   caller pushes `Routes.Player`. Resolving *which* item a Play tap means (a series plays its
 *   next-up episode) happens here rather than in the caller, because only this screen knows the
 *   rows it loaded.
 */
@Composable
fun ItemDetailScreen(
    viewModel: ItemDetailViewModel,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.userMessage?.let { stringResource(it.textRes()) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ItemDetailContent(
            state = state,
            onRetry = viewModel::refresh,
            onItemClick = onItemClick,
            onPlay = onPlay,
            actions =
                DetailActionHandlers(
                    onPlay = { state.playTarget?.let { target -> onPlay(target.id, playbackStartTicks(target)) } },
                    onDownload = viewModel::onDownloadClick,
                    onToggleWatched = viewModel::toggleWatched,
                    onToggleFavorite = viewModel::toggleFavorite,
                ),
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(Dimens.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.detail_back),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Stateless detail rendering — a pure function of [state], so it previews without a ViewModel.
 */
@Composable
fun ItemDetailContent(
    state: ItemDetailUiState,
    onRetry: () -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    actions: DetailActionHandlers,
    modifier: Modifier = Modifier,
) {
    val detail = state.item
    when {
        state.isLoading -> LoadingState(modifier = modifier)

        state.errorMessage != null ->
            ErrorState(message = state.errorMessage, modifier = modifier, onRetry = onRetry)

        detail == null ->
            EmptyState(message = stringResource(R.string.detail_empty), modifier = modifier)

        else ->
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                DetailSections(
                    state = state,
                    detail = detail,
                    isWide = maxWidth >= WIDE_BREAKPOINT,
                    onItemClick = onItemClick,
                    onPlay = onPlay,
                    actions = actions,
                )
            }
    }
}

@Composable
private fun DetailSections(
    state: ItemDetailUiState,
    detail: JellyfinItem,
    isWide: Boolean,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    actions: DetailActionHandlers,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Dimens.SpaceExtraLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        item(key = SECTION_BACKDROP) {
            BackdropHeader(
                imageUrl = detail.backdropImageUrl ?: detail.thumbImageUrl ?: detail.primaryImageUrl,
                title = detail.displayTitle,
                height = if (isWide) WIDE_BACKDROP_HEIGHT else Dimens.BackdropHeight,
            )
        }

        item(key = SECTION_HEADER) {
            DetailHeader(item = detail, isWide = isWide, actions = actions)
        }

        state.nextUp?.let { next ->
            item(key = SECTION_NEXT_UP) {
                MediaRow(
                    title = stringResource(R.string.detail_section_next_up),
                    items = listOf(next),
                    key = JellyfinItem::id,
                ) { episode -> ThumbCard(item = episode, onClick = { onItemClick(episode) }) }
            }
        }

        if (state.seasons.isNotEmpty()) {
            item(key = SECTION_SEASONS) {
                MediaRow(
                    title = stringResource(R.string.detail_section_seasons),
                    items = state.seasons,
                    key = JellyfinItem::id,
                ) { season -> PosterCard(item = season, onClick = { onItemClick(season) }) }
            }
        }

        if (state.episodes.isNotEmpty()) {
            item(key = SECTION_EPISODES) {
                SectionTitle(text = stringResource(R.string.detail_section_episodes))
            }
            items(items = state.episodes, key = JellyfinItem::id) { episode ->
                EpisodeRow(
                    episode = episode,
                    onClick = { onItemClick(episode) },
                    onPlay = { onPlay(episode.id, playbackStartTicks(episode)) },
                )
            }
        }

        if (state.similar.isNotEmpty()) {
            item(key = SECTION_SIMILAR) {
                MediaRow(
                    title = stringResource(R.string.detail_section_similar),
                    items = state.similar,
                    key = JellyfinItem::id,
                ) { related -> PosterCard(item = related, onClick = { onItemClick(related) }) }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(horizontal = Dimens.ScreenPadding),
    )
}

private fun UserMessage.textRes(): Int =
    when (this) {
        UserMessage.DownloadNotAvailableYet -> R.string.detail_message_download_unavailable
        UserMessage.UserDataWriteFailed -> R.string.detail_message_user_data_failed
    }

private const val SECTION_BACKDROP = "section-backdrop"
private const val SECTION_HEADER = "section-header"
private const val SECTION_NEXT_UP = "section-next-up"
private const val SECTION_SEASONS = "section-seasons"
private const val SECTION_EPISODES = "section-episodes"
private const val SECTION_SIMILAR = "section-similar"

/** Above this width the header lays out side by side — roughly a tablet in portrait. */
private val WIDE_BREAKPOINT = 720.dp

private val WIDE_BACKDROP_HEIGHT = 320.dp
