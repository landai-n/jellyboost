package dev.jellyboost.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.AlbumCard
import dev.jellyboost.core.ui.component.ArtistCard
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/**
 * `contentWindowInsets = WindowInsets(0)` because this screen's own header carries the status-bar
 * inset, as on every pushed destination.
 */
@Composable
fun MusicLibraryScreen(
    viewModel: MusicLibraryViewModel,
    onAlbumClick: (JellyfinItem) -> Unit,
    onArtistClick: (JellyfinItem) -> Unit,
    onPlaylistClick: (JellyfinItem) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val albums = viewModel.albums.collectAsLazyPagingItems()
    val artists = viewModel.artists.collectAsLazyPagingItems()
    val playlists = viewModel.playlists.collectAsLazyPagingItems()

    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0)) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            MusicScreenGlow()

            Column(modifier = Modifier.fillMaxSize()) {
                MusicLibraryHeader(title = state.libraryName, onBack = onBack, onHome = onHome)

                MusicLibraryTabRow(selectedTab = state.selectedTab, onSelectTab = viewModel::selectTab)

                when (state.selectedTab) {
                    MusicLibraryTab.ALBUMS ->
                        AlbumGrid(items = albums, onItemClick = onAlbumClick, modifier = Modifier.weight(1f))

                    MusicLibraryTab.ARTISTS ->
                        ArtistGrid(items = artists, onItemClick = onArtistClick, modifier = Modifier.weight(1f))

                    MusicLibraryTab.PLAYLISTS ->
                        PlaylistGrid(items = playlists, onItemClick = onPlaylistClick, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MusicLibraryHeader(
    title: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = HeaderPadding, end = HeaderPadding, top = HeaderPadding, bottom = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.music_library_back),
            onClick = onBack,
        )
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.music_library_home),
            onClick = onHome,
        )
        Text(
            text = title,
            style = JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceExtraSmall),
        )
    }
}

/** Rebuilt from `DownloadsTabRow`'s shape: no `:feature` → `:feature` dependency exists to share it. */
@Composable
private fun MusicLibraryTabRow(
    selectedTab: MusicLibraryTab,
    onSelectTab: (MusicLibraryTab) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding, vertical = Dimens.SpaceSmall)
                .clip(CircleShape)
                .background(color = GlassDefaults.Fill, shape = CircleShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                .padding(TabBarPadding),
        horizontalArrangement = Arrangement.spacedBy(TabGap),
    ) {
        MusicLibraryTab.entries.forEach { tab ->
            MusicLibrarySegment(
                selected = tab == selectedTab,
                label = stringResource(tab.titleRes()),
                onClick = { onSelectTab(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MusicLibrarySegment(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The app background `#101010`, not `Color.Black`: `GlassBottomNav` and `DownloadsTabRow` both
    // put the page colour on the selected white pill.
    val contentColor =
        if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            modifier
                .height(Dimens.PillHeightSmall)
                .clip(CircleShape)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .background(color = if (selected) Color.White else Color.Transparent, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = TabLabelStyle, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun MusicLibraryTab.titleRes(): Int =
    when (this) {
        MusicLibraryTab.ALBUMS -> R.string.music_library_tab_albums
        MusicLibraryTab.ARTISTS -> R.string.music_library_tab_artists
        MusicLibraryTab.PLAYLISTS -> R.string.music_library_tab_playlists
    }

@Composable
private fun AlbumGrid(
    items: LazyPagingItems<JellyfinItem>,
    onItemClick: (JellyfinItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    MusicGrid(
        items = items,
        emptyMessage = stringResource(R.string.music_library_empty_albums),
        modifier = modifier,
    ) { item -> AlbumCard(item = item, onClick = { onItemClick(item) }, width = Dp.Unspecified) }
}

@Composable
private fun ArtistGrid(
    items: LazyPagingItems<JellyfinItem>,
    onItemClick: (JellyfinItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    MusicGrid(
        items = items,
        emptyMessage = stringResource(R.string.music_library_empty_artists),
        modifier = modifier,
    ) { item -> ArtistCard(item = item, onClick = { onItemClick(item) }, width = Dp.Unspecified) }
}

@Composable
private fun PlaylistGrid(
    items: LazyPagingItems<JellyfinItem>,
    onItemClick: (JellyfinItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    MusicGrid(
        items = items,
        emptyMessage = stringResource(R.string.music_library_empty_playlists),
        modifier = modifier,
    ) { item -> PosterCard(item = item, onClick = { onItemClick(item) }, width = Dp.Unspecified, showTitle = true) }
}

@Composable
private fun MusicGrid(
    items: LazyPagingItems<JellyfinItem>,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    card: @Composable (JellyfinItem) -> Unit,
) {
    val refreshState = items.loadState.refresh

    when {
        refreshState is LoadState.Loading && items.itemCount == 0 -> LoadingState(modifier = modifier)

        refreshState is LoadState.Error ->
            ErrorState(message = refreshState.error.toPagingMessage(), modifier = modifier, onRetry = items::retry)

        items.itemCount == 0 && refreshState is LoadState.NotLoading ->
            EmptyState(message = emptyMessage, modifier = modifier)

        else -> {
            LazyVerticalGrid(
                state = rememberLazyGridState(),
                columns = GridCells.Adaptive(MIN_CELL_WIDTH),
                modifier = modifier.fillMaxSize(),
                contentPadding =
                    musicListContentPadding(
                        bottom = HeaderPadding,
                        top = Dimens.SpaceExtraSmall,
                        horizontal = HeaderPadding,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
            ) {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.id },
                    contentType = items.itemContentType { GRID_CELL_CONTENT_TYPE },
                ) { index ->
                    items[index]?.let { item -> card(item) }
                }
            }
        }
    }
}

private val MIN_CELL_WIDTH = Dimens.PosterWidth
private val HeaderPadding = 20.dp
private val TabBarPadding = 4.dp
private val TabGap = 4.dp
private val TabLabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)
private const val GRID_CELL_CONTENT_TYPE = "music-grid-card"
