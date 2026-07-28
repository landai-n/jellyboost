package dev.jellyfinnative.data

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView

/**
 * The one media-browsing surface the UI talks to.
 *
 * Both the online (SDK) and offline (Room) implementations satisfy this contract and return the
 * exact same domain models, which is what lets a single set of screens serve streamed and
 * downloaded media (docs/PLAN.md, "Data layer"). `BaseItemDto` and `ItemEntity` never appear here.
 *
 * M2 declares only the home-screen surface; the paged library grid, search and item detail extend
 * this interface in M3 and M4.
 */
interface JellyfinRepository {
    /**
     * The current user's libraries, filtered to the kinds v1 supports (movies and TV shows).
     *
     * Backs the home screen's *My Media* row and the Libraries tab.
     */
    suspend fun getUserViews(): AppResult<List<LibraryView>>

    /**
     * Partially-watched items, newest first — the *Continue watching* row.
     *
     * @param limit maximum number of items; matches jellyfin-web's home row size by default.
     */
    suspend fun getResumeItems(limit: Int = DEFAULT_RESUME_LIMIT): AppResult<List<JellyfinItem>>

    /**
     * The next unwatched episode of each series in progress — the *Next up* row.
     *
     * @param limit maximum number of items.
     */
    suspend fun getNextUp(limit: Int = DEFAULT_NEXT_UP_LIMIT): AppResult<List<JellyfinItem>>

    /**
     * Recently added items in one library — the *Latest &lt;library&gt;* rows, one per library.
     *
     * @param parentId id of the [LibraryView] to pull from.
     * @param limit maximum number of items.
     */
    suspend fun getLatestMedia(
        parentId: String,
        limit: Int = DEFAULT_LATEST_LIMIT,
    ): AppResult<List<JellyfinItem>>

    companion object {
        /** Row size jellyfin-web uses for *Continue watching* (DECISIONS.md 2026-07-28). */
        const val DEFAULT_RESUME_LIMIT = 12

        /** Row size jellyfin-web uses for *Next up* (DECISIONS.md 2026-07-28). */
        const val DEFAULT_NEXT_UP_LIMIT = 24

        /** Row size jellyfin-web uses for the per-library *Latest* rows. */
        const val DEFAULT_LATEST_LIMIT = 16
    }
}
