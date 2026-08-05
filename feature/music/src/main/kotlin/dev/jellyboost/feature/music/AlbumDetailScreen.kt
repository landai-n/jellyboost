package dev.jellyboost.feature.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/**
 * An album's header (art, title, artist, year, favourite) and its tracks, disc-grouped when the
 * album has more than one (M13 Phase 2, docs/notes/music-m13-plan.md).
 *
 * Play and Shuffle hand the album's tracks straight to [onPlay]/[onShuffle], which
 * `JellyfinNavHost` wires to `MusicPlaybackViewModel` (M13 Phase 3's queue).
 *
 * @param onPlay `(tracks, startIndex)` — a track row was tapped, or the Play button at index 0.
 * @param onShuffle `(tracks)` — the Shuffle button; starts the queue shuffled from the top.
 */
@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    onArtistClick: (JellyfinItem) -> Unit,
    onPlay: (tracks: List<JellyfinItem>, startIndex: Int) -> Unit,
    onShuffle: (tracks: List<JellyfinItem>) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage = state.errorMessage?.toMessage()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingState()

            errorMessage != null -> ErrorState(message = errorMessage, onRetry = viewModel::refresh)

            state.album == null -> EmptyState(message = stringResource(R.string.music_album_empty))

            else ->
                AlbumDetailContent(
                    state = state,
                    onArtistClick = onArtistClick,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onTrackClick = { index -> onPlay(state.tracks, index) },
                    onPlayAlbum = { onPlay(state.tracks, 0) },
                    onShuffle = { onShuffle(state.tracks) },
                )
        }

        AlbumOverlayNav(onBack = onBack, onHome = onHome)
    }
}

@Composable
private fun AlbumOverlayNav(
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
            contentDescription = stringResource(R.string.music_album_back),
            onClick = onBack,
            surfaceTint = GlassDefaults.ChromeFill,
        )
        Box(modifier = Modifier.weight(1f))
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.music_album_home),
            onClick = onHome,
            surfaceTint = GlassDefaults.ChromeFill,
        )
    }
}

@Composable
private fun AlbumDetailContent(
    state: AlbumDetailUiState,
    onArtistClick: (JellyfinItem) -> Unit,
    onToggleFavorite: (JellyfinItem) -> Unit,
    onTrackClick: (index: Int) -> Unit,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
) {
    val album = state.album ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Dimens.SpaceExtraLarge),
    ) {
        item(key = SECTION_HEADER) {
            AlbumHeader(album = album, onArtistClick = onArtistClick, onToggleFavorite = onToggleFavorite)
            Spacer(modifier = Modifier.height(Dimens.SpaceMedium))
            AlbumTransportRow(onPlay = onPlayAlbum, onShuffle = onShuffle)
            Spacer(modifier = Modifier.height(Dimens.SpaceMedium))
        }

        if (state.tracks.isEmpty()) {
            item(key = SECTION_EMPTY) {
                EmptyState(message = stringResource(R.string.music_album_no_tracks))
            }
        } else {
            var runningIndex = 0
            state.tracksByDisc.forEach { (disc, discTracks) ->
                if (state.isMultiDisc) {
                    item(key = "disc-$disc") {
                        Text(
                            text = stringResource(R.string.music_album_disc_number, disc),
                            style = DiscHeaderStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceSmall),
                        )
                    }
                }
                discTracks.forEach { track ->
                    val trackIndex = runningIndex
                    runningIndex++
                    item(key = track.id) {
                        TrackRow(
                            track = track,
                            index = track.indexNumber,
                            onClick = { onTrackClick(trackIndex) },
                            onToggleFavorite = { onToggleFavorite(track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    album: JellyfinItem,
    onArtistClick: (JellyfinItem) -> Unit,
    onToggleFavorite: (JellyfinItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(Dimens.SpaceLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Clears the overlaid back/home row before the art starts.
        Box(modifier = Modifier.size(Dimens.MinTouchTarget))

        JellyfinAsyncImage(
            url = album.primaryImageUrl,
            contentDescription = album.displayTitle,
            modifier = Modifier.size(AlbumArtSize).clip(RoundedCornerShape(Dimens.CardCornerRadius)),
        )

        Spacer(modifier = Modifier.height(Dimens.SpaceMedium))

        Text(
            text = album.displayTitle,
            style = JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val artist = album.artistRefs.firstOrNull()
        val artistLine = artist?.name ?: album.albumArtist
        if (artistLine != null) {
            Text(
                text = artistLine,
                style = AlbumArtistStyle,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(top = Dimens.SpaceExtraSmall)
                        .then(
                            if (artist != null) {
                                Modifier.clickable {
                                    onArtistClick(
                                        JellyfinItem(id = artist.id, name = artist.name, type = ItemType.MUSIC_ARTIST),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
            )
        }

        album.productionYear?.let { year ->
            Text(
                text = year.toString(),
                style = AlbumYearStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.SpaceExtraSmall),
            )
        }

        Spacer(modifier = Modifier.height(Dimens.SpaceMedium))

        val isFavorite = album.userData.isFavorite
        GlassIconButton(
            icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription =
                stringResource(
                    if (isFavorite) R.string.music_album_remove_favorite else R.string.music_album_add_favorite,
                ),
            onClick = { onToggleFavorite(album) },
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else GlassIconTint,
        )
    }
}

/** Play / Shuffle — hands the album straight to [MusicPlaybackViewModel][onPlay]'s queue. */
@Composable
private fun AlbumTransportRow(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceLarge),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        PrimaryPillButton(
            text = stringResource(R.string.music_album_play),
            onClick = onPlay,
            leadingIcon = Icons.Filled.PlayArrow,
            modifier = Modifier.weight(1f),
        )
        GhostPillButton(
            text = stringResource(R.string.music_album_shuffle),
            onClick = onShuffle,
            leadingIcon = Icons.Filled.Shuffle,
            modifier = Modifier.weight(1f),
        )
    }
}

private val AlbumArtSize = 220.dp

private val DiscHeaderStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W600)

private val AlbumArtistStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W500)

private val AlbumYearStyle = TextStyle(fontSize = 13.sp)

private const val SECTION_HEADER = "album-header"
private const val SECTION_EMPTY = "album-empty"
