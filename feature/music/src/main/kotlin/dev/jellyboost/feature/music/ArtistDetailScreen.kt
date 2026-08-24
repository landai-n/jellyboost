package dev.jellyboost.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.AlbumCard
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/**
 * An artist's header (circular art, name, favourite), their albums and their top tracks.
 *
 * @param onAlbumClick an album card in the "Albums" row was tapped.
 * @param onTrackClick `(tracks, startIndex)` — a top-track row was tapped; wired to the queue
 *   exactly as [AlbumDetailScreen]'s `onPlay` — see that screen's KDoc.
 * @param onStartRadio the artist itself — "Start radio", the header action next to
 *   the favourite heart; see [AlbumDetailScreen]'s `onStartRadio` for the wiring this mirrors.
 */
@Composable
fun ArtistDetailScreen(
    viewModel: ArtistDetailViewModel,
    onAlbumClick: (JellyfinItem) -> Unit,
    onTrackClick: (tracks: List<JellyfinItem>, startIndex: Int) -> Unit,
    onStartRadio: (JellyfinItem) -> Unit,
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

            state.artist == null -> EmptyState(message = stringResource(R.string.music_artist_empty))

            else ->
                ArtistDetailContent(
                    state = state,
                    onAlbumClick = onAlbumClick,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onTrackClick = { index -> onTrackClick(state.visibleTopTracks, index) },
                    onExpandTopTracks = viewModel::expandTopTracks,
                    onStartRadio = { state.artist?.let(onStartRadio) },
                )
        }

        ArtistOverlayNav(onBack = onBack, onHome = onHome)
    }
}

@Composable
private fun ArtistOverlayNav(
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
            contentDescription = stringResource(R.string.music_artist_back),
            onClick = onBack,
        )
        Box(modifier = Modifier.weight(1f))
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.music_artist_home),
            onClick = onHome,
        )
    }
}

@Composable
private fun ArtistDetailContent(
    state: ArtistDetailUiState,
    onAlbumClick: (JellyfinItem) -> Unit,
    onToggleFavorite: (JellyfinItem) -> Unit,
    onTrackClick: (index: Int) -> Unit,
    onExpandTopTracks: () -> Unit,
    onStartRadio: () -> Unit,
) {
    val artist = state.artist ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = musicListContentPadding(bottom = Dimens.SpaceExtraLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        item(key = SECTION_HEADER) {
            ArtistHeader(artist = artist, onToggleFavorite = onToggleFavorite, onStartRadio = onStartRadio)
        }

        if (state.albums.isNotEmpty()) {
            item(key = SECTION_ALBUMS) {
                MediaRow(
                    title = stringResource(R.string.music_artist_section_albums),
                    items = state.albums,
                    key = JellyfinItem::id,
                ) { album -> AlbumCard(item = album, onClick = { onAlbumClick(album) }) }
            }
        }

        if (state.topTracks.isNotEmpty()) {
            item(key = SECTION_TOP_TRACKS_TITLE) {
                Text(
                    text = stringResource(R.string.music_artist_section_top_tracks),
                    style = JellyfinTypeExtras.SectionTitle,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                )
            }
            itemsIndexed(state.visibleTopTracks) { index, track ->
                TrackRow(
                    track = track,
                    index = index + 1,
                    onClick = { onTrackClick(index) },
                    onToggleFavorite = { onToggleFavorite(track) },
                )
            }
            if (state.hasMoreTopTracks && !state.topTracksExpanded) {
                item(key = SECTION_TOP_TRACKS_MORE) {
                    TextButton(onClick = onExpandTopTracks, modifier = Modifier.padding(start = Dimens.SpaceMedium)) {
                        Text(text = stringResource(R.string.music_artist_top_tracks_show_more))
                    }
                }
            }
        }
    }
}

/** `LazyColumn.items` with an index, matching the shape every other section on this screen uses. */
private fun LazyListScope.itemsIndexed(
    items: List<JellyfinItem>,
    itemContent: @Composable (index: Int, item: JellyfinItem) -> Unit,
) {
    items.forEachIndexed { index, track ->
        item(key = "track-${track.id}") { itemContent(index, track) }
    }
}

@Composable
private fun ArtistHeader(
    artist: JellyfinItem,
    onToggleFavorite: (JellyfinItem) -> Unit,
    onStartRadio: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(Dimens.SpaceLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(Dimens.MinTouchTarget))

        JellyfinAsyncImage(
            url = artist.primaryImageUrl,
            contentDescription = artist.displayTitle,
            modifier = Modifier.size(ArtistArtSize).aspectRatio(1f).clip(CircleShape),
        )

        Spacer(modifier = Modifier.height(Dimens.SpaceMedium))

        Text(
            text = artist.displayTitle,
            style = JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(Dimens.SpaceMedium))

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
            val isFavorite = artist.userData.isFavorite
            GlassIconButton(
                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription =
                    stringResource(
                        if (isFavorite) R.string.music_artist_remove_favorite else R.string.music_artist_add_favorite,
                    ),
                onClick = { onToggleFavorite(artist) },
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else GlassIconTint,
            )
            GlassIconButton(
                icon = Icons.Filled.Radio,
                contentDescription = stringResource(R.string.music_artist_start_radio),
                onClick = onStartRadio,
            )
        }
    }
}

private val ArtistArtSize = 160.dp

private const val SECTION_HEADER = "artist-header"
private const val SECTION_ALBUMS = "artist-albums"
private const val SECTION_TOP_TRACKS_TITLE = "artist-top-tracks-title"
private const val SECTION_TOP_TRACKS_MORE = "artist-top-tracks-more"
