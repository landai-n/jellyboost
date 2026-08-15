package dev.jellyboost.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/**
 * A playlist's tracks, **view-only** — playlist editing is out of M13's scope
 * (docs/notes/music-m13-plan.md).
 *
 * @param onTrackClick `(tracks, startIndex)` — a track row was tapped. No-op until M13 Phase 3
 *   wires an actual queue, exactly as [AlbumDetailScreen]'s `onPlay` — see that screen's KDoc.
 */
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    onTrackClick: (tracks: List<JellyfinItem>, startIndex: Int) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage = state.errorMessage?.toMessage()

    Box(modifier = modifier.fillMaxSize()) {
        // Behind everything, anchored to the top of the window — see [MusicScreenGlow].
        MusicScreenGlow()

        when {
            state.isLoading -> LoadingState()

            errorMessage != null -> ErrorState(message = errorMessage, onRetry = viewModel::refresh)

            state.playlist == null -> EmptyState(message = stringResource(R.string.music_playlist_empty))

            else ->
                PlaylistDetailContent(
                    state = state,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onTrackClick = { index -> onTrackClick(state.tracks, index) },
                )
        }

        PlaylistOverlayNav(onBack = onBack, onHome = onHome)
    }
}

@Composable
private fun PlaylistOverlayNav(
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(Dimens.SpaceLarge),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.music_playlist_back),
            onClick = onBack,
            surfaceTint = GlassDefaults.ChromeFill,
        )
        Box(modifier = Modifier.weight(1f))
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.music_playlist_home),
            onClick = onHome,
            surfaceTint = GlassDefaults.ChromeFill,
        )
    }
}

@Composable
private fun PlaylistDetailContent(
    state: PlaylistDetailUiState,
    onToggleFavorite: (JellyfinItem) -> Unit,
    onTrackClick: (index: Int) -> Unit,
) {
    val playlist = state.playlist ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = musicListContentPadding(bottom = Dimens.SpaceExtraLarge),
    ) {
        item(key = SECTION_HEADER) {
            PlaylistHeader(playlist = playlist, trackCount = state.tracks.size)
        }

        if (state.tracks.isEmpty()) {
            item(key = SECTION_EMPTY) {
                EmptyState(
                    message = stringResource(R.string.music_playlist_no_tracks),
                    icon = Icons.Outlined.QueueMusic,
                )
            }
        } else {
            state.tracks.forEachIndexed { index, track ->
                // Position-qualified: a playlist may hold the same track twice, and a bare
                // `track.id` would be a duplicate lazy key — an IllegalArgumentException at
                // composition time. Same convention as the queue sheet's rows.
                item(key = "$index:${track.id}") {
                    TrackRow(
                        track = track,
                        index = index + 1,
                        onClick = { onTrackClick(index) },
                        onToggleFavorite = { onToggleFavorite(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: JellyfinItem,
    trackCount: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(Dimens.SpaceLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.height(Dimens.MinTouchTarget))

        Text(
            text = playlist.displayTitle,
            style = JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (trackCount > 0) {
            Spacer(modifier = Modifier.height(Dimens.SpaceExtraSmall))
            Text(
                text = pluralStringResource(R.plurals.music_playlist_track_count, trackCount, trackCount),
                style = JellyfinTypeExtras.SeeAll,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SECTION_HEADER = "playlist-header"
private const val SECTION_EMPTY = "playlist-empty"
