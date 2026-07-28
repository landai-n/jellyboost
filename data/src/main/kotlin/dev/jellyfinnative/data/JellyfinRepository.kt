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

    // ---- M4 — item detail -------------------------------------------------------------------

    /**
     * Re-fetches one item in full — overview, taglines, genres, people, media sources, streams,
     * chapters and trickplay.
     *
     * Detail screens deliberately do not reuse the lean item a list handed them: the plan follows
     * Swiftfin here, where lists are minimal and the detail/playback path fetches everything
     * (docs/PLAN.md, "Screens" → ItemDetail).
     */
    suspend fun getItem(id: String): AppResult<JellyfinItem>

    /** The seasons of a series, in server order — the seasons row on a series detail page. */
    suspend fun getSeasons(seriesId: String): AppResult<List<JellyfinItem>>

    /**
     * The episodes of one season, in server order.
     *
     * [seriesId] is not redundant: the server's episode endpoint is rooted at the series
     * (`/Shows/{seriesId}/Episodes`) and treats the season as a filter. A season item always
     * carries its `seriesId`, so callers have it.
     */
    suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
    ): AppResult<List<JellyfinItem>>

    /**
     * The next unwatched episode of one series, or `null` when the series is fully watched.
     *
     * Distinct from [getNextUp], which spans every series for the home row.
     */
    suspend fun getNextUpForSeries(seriesId: String): AppResult<JellyfinItem?>

    /** Server-recommended related items — the *More like this* row. */
    suspend fun getSimilarItems(
        id: String,
        limit: Int = DEFAULT_SIMILAR_LIMIT,
    ): AppResult<List<JellyfinItem>>

    // ---- end M4 ------------------------------------------------------------------------------

    companion object {
        /** Row size jellyfin-web uses for *Continue watching* (DECISIONS.md 2026-07-28). */
        const val DEFAULT_RESUME_LIMIT = 12

        /** Row size jellyfin-web uses for *Next up* (DECISIONS.md 2026-07-28). */
        const val DEFAULT_NEXT_UP_LIMIT = 24

        /** Row size jellyfin-web uses for the per-library *Latest* rows. */
        const val DEFAULT_LATEST_LIMIT = 16
    }
}

// ---- M4 — item detail ------------------------------------------------------------------------

/**
 * Row size for the detail page's *More like this* row.
 *
 * Declared at file scope rather than inside [JellyfinRepository.Companion] so that the M4 surface
 * stays one append-only block while M3 extends the same file in parallel.
 */
const val DEFAULT_SIMILAR_LIMIT = 12
