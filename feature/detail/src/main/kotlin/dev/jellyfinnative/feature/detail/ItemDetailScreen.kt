package dev.jellyfinnative.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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
    val message = state.userMessage?.let { userMessageText(it) }

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

        // This screen is a pushed destination, so per `AppScaffold`'s inset contract it gets none
        // of its own — the inset has to live on the button rather than the surrounding `Box`,
        // since the backdrop behind it is meant to draw edge-to-edge under the status bar.
        IconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(Dimens.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.detail_back),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        if (state.showDeleteConfirmation) {
            DeleteDownloadDialog(
                onDismiss = viewModel::dismissDeleteConfirmation,
                onConfirm = viewModel::confirmDeleteDownload,
            )
        }
    }
}

/**
 * Confirms removing a download from this screen's Download button (docs/POLISH.md — deleting a
 * downloaded file from the detail screen used to happen with no confirmation at all).
 *
 * Precedent: `SignOutDialog` in `:feature:settings`.
 */
@Composable
private fun DeleteDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.detail_delete_download_dialog_title)) },
        text = { Text(text = stringResource(R.string.detail_delete_download_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.detail_delete_download_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.detail_delete_download_dialog_cancel))
            }
        },
    )
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
                    backdropHeight = backdropHeight(maxWidth = maxWidth, maxHeight = maxHeight),
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
    backdropHeight: Dp,
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
            // No title here — DetailFacts (below, in the header section) already renders the
            // headline once; drawing it again over the backdrop duplicated it.
            BackdropHeader(
                imageUrl = detail.backdropImageUrl ?: detail.thumbImageUrl ?: detail.primaryImageUrl,
                height = backdropHeight,
            )
        }

        item(key = SECTION_HEADER) {
            DetailHeader(
                item = detail,
                isWide = isWide,
                downloadState = state.downloadState,
                actions = actions,
            )
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

/** Renders a one-shot [message] as snackbar copy (precedent: `:feature:auth`'s `authErrorText`). */
@Composable
private fun userMessageText(message: UserMessage): String =
    when (message) {
        UserMessage.DownloadQueued -> stringResource(R.string.detail_message_download_queued)
        UserMessage.DownloadFailed -> stringResource(R.string.detail_message_download_failed)
        UserMessage.DownloadDeleted -> stringResource(R.string.detail_message_download_deleted)
        UserMessage.DownloadDeleteFailed -> stringResource(R.string.detail_message_download_delete_failed)
        UserMessage.UserDataWriteFailed -> stringResource(R.string.detail_message_user_data_failed)
        is UserMessage.DownloadCancelledKeepingFinished ->
            pluralStringResource(
                R.plurals.detail_message_download_cancelled_kept,
                message.keptCount,
                message.keptCount,
            )
    }

private const val SECTION_BACKDROP = "section-backdrop"
private const val SECTION_HEADER = "section-header"
private const val SECTION_NEXT_UP = "section-next-up"
private const val SECTION_SEASONS = "section-seasons"
private const val SECTION_EPISODES = "section-episodes"
private const val SECTION_SIMILAR = "section-similar"

/**
 * How tall the backdrop banner is for a viewport of [maxWidth] × [maxHeight].
 *
 * In **portrait** the banner is a share of the viewport *height* rather than a fixed number of dp:
 * a tablet in portrait is wide enough to take the [WIDE_BACKDROP_HEIGHT] branch on a screen twice
 * as tall, which left the poster stranded at the top with dead space below it (docs/POLISH.md).
 * The width-derived value stays as the floor — a proportional banner never gets *shorter* than the
 * fixed one — and [MAX_BACKDROP_HEIGHT] keeps it from pushing the facts and Play button off-screen
 * on a very tall device.
 *
 * In **landscape** vertical space is what is scarce, so the width-based height is used unchanged.
 */
private fun backdropHeight(
    maxWidth: Dp,
    maxHeight: Dp,
): Dp {
    val fixed = if (maxWidth >= WIDE_BREAKPOINT) WIDE_BACKDROP_HEIGHT else Dimens.BackdropHeight
    val isPortrait = maxHeight > maxWidth
    return if (isPortrait) {
        (maxHeight * PORTRAIT_BACKDROP_FRACTION).coerceIn(fixed, MAX_BACKDROP_HEIGHT)
    } else {
        fixed
    }
}

/** Above this width the header lays out side by side — roughly a tablet in portrait. */
private val WIDE_BREAKPOINT = 720.dp

private val WIDE_BACKDROP_HEIGHT = 320.dp

/** Share of the viewport height the banner claims in portrait. */
private const val PORTRAIT_BACKDROP_FRACTION = 0.40f

/** Ceiling for the proportional portrait banner, so the header below it stays in view. */
private val MAX_BACKDROP_HEIGHT = 560.dp
