package dev.jellyboost.core.common

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation Compose routes.
 *
 * They live in `:core:common` so that feature modules can navigate to each other without ever
 * depending on each other (see docs/PLAN.md, "Project skeleton").
 */
object Routes {
    /** Top-level destinations backing the bottom navigation bar. */
    @Serializable
    data object Home

    @Serializable
    data object Libraries

    @Serializable
    data object Search

    @Serializable
    data object Downloads

    /** Destinations reachable from the top-level ones. */
    @Serializable
    data object Settings

    @Serializable
    data object ServerSetup

    @Serializable
    data object Login

    /**
     * The dedicated SyncPlay section (M11 Phase 5): the group list, join/create/leave.
     *
     * Reached from the home top bar's Groups action — see `AppTopBar` — the same way `Settings` is
     * reached from its overflow menu; both are pushed destinations with no arguments of their own.
     */
    @Serializable
    data object SyncPlay

    @Serializable
    data class Library(
        val libraryId: String,
    )

    @Serializable
    data class ItemDetail(
        val itemId: String,
    )

    /**
     * The full-screen video player (M5).
     *
     * @param mediaSourceId which of the item's media sources to play; `null` lets the server pick
     *   the default one (see `PlaybackInfoResolver`'s dash-less media-source-id quirk).
     * @param startPositionTicks where playback starts, in Jellyfin ticks — the item's
     *   `playbackPositionTicks` for Resume, `0` for Play from the beginning.
     */
    @Serializable
    data class Player(
        val itemId: String,
        val mediaSourceId: String? = null,
        val startPositionTicks: Long = 0L,
    )

    // M3 — library & search
    //
    // Search reuses the top-level [Search] destination declared above; only the grid needs a new
    // route, because it carries the library name for its top bar (a second `getUserViews` round
    // trip just to render a title would be wasteful, and the name is already on screen when the
    // user taps through).

    @Serializable
    data class LibraryGrid(
        val libraryId: String,
        val libraryName: String,
    )
}
