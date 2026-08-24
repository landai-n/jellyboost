package dev.jellyboost.feature.music

/**
 * Each tab keeps its **own** `Pager` rather than one being swapped between three queries: switching
 * tabs must not re-fetch a grid the user already scrolled through.
 */
enum class MusicLibraryTab {
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
}

/** The grids are not here: they arrive as `PagingData` flows, whose load states Paging owns. */
data class MusicLibraryUiState(
    val libraryName: String = "",
    val selectedTab: MusicLibraryTab = MusicLibraryTab.ALBUMS,
)
