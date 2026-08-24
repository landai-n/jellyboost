package dev.jellyboost.feature.music

/**
 * The three tabs a music library opens onto.
 *
 * Each tab is its own paged grid ([MusicLibraryViewModel.albums]/`.artists`/`.playlists`), kept
 * separate from the ones the user is not looking at rather than swapping one `Pager` between three
 * queries — the same reason the library grid's own `Pager` is rebuilt only when its query actually
 * changes: switching tabs must not re-fetch a grid the user already scrolled through.
 */
enum class MusicLibraryTab {
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
}

/**
 * Everything [MusicLibraryScreen] draws outside its three paged grids.
 *
 * The grids themselves are not here: they arrive as `PagingData` flows, whose loading and error
 * states Paging owns (see `LibraryUiState`'s identical split for the precedent).
 */
data class MusicLibraryUiState(
    val libraryName: String = "",
    val selectedTab: MusicLibraryTab = MusicLibraryTab.ALBUMS,
)
