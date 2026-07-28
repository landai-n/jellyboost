package dev.jellyfinnative.core.common

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

    @Serializable
    data class Library(
        val libraryId: String,
    )

    @Serializable
    data class ItemDetail(
        val itemId: String,
    )

    @Serializable
    data class Player(
        val itemId: String,
        val startPositionTicks: Long = 0L,
    )
}
