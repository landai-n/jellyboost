package dev.jellyboost.data

import androidx.paging.PagingData
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.music.Lyrics
import kotlinx.coroutines.flow.Flow

/**
 * The online (SDK) and offline (Room) implementations return the *same* domain models, which is what
 * lets one set of screens serve both. `BaseItemDto` and `ItemEntity` never appear here.
 */
@Suppress(
    "TooManyFunctions",
)
interface JellyfinRepository {
    /** Filtered to [dev.jellyboost.core.common.model.CollectionKind.SUPPORTED] — movies, TV, music. */
    suspend fun getUserViews(): AppResult<List<LibraryView>>

    /** Partially-watched items, newest first — the *Continue watching* row. */
    suspend fun getResumeItems(limit: Int = DEFAULT_RESUME_LIMIT): AppResult<List<JellyfinItem>>

    /** The next unwatched episode of *each* series in progress — the *Next up* row. */
    suspend fun getNextUp(limit: Int = DEFAULT_NEXT_UP_LIMIT): AppResult<List<JellyfinItem>>

    /** @param parentId a [LibraryView] id, not a folder id. */
    suspend fun getLatestMedia(
        parentId: String,
        limit: Int = DEFAULT_LATEST_LIMIT,
    ): AppResult<List<JellyfinItem>>

    // ---- item detail ---------------------------------------------------------------------------

    /**
     * Re-fetches one item in full — overview, genres, people, media sources, streams, chapters,
     * trickplay. Detail screens must not reuse the lean item a list handed them.
     */
    suspend fun getItem(id: String): AppResult<JellyfinItem>

    /** In server order. */
    suspend fun getSeasons(seriesId: String): AppResult<List<JellyfinItem>>

    /**
     * In server order. [seriesId] is not redundant: the endpoint is rooted at the series
     * (`/Shows/{seriesId}/Episodes`) and treats the season as a filter.
     */
    suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
    ): AppResult<List<JellyfinItem>>

    /**
     * Exists for SyncPlay: handed a one-episode group queue, jellyfin-web's
     * `translateItemsForPlayback` expands it to every following episode, then indexes the server's
     * playlist by the *expanded* length and drops the whole queue update. Sending the expansion
     * ourselves keeps the two lengths equal.
     */
    suspend fun getSeriesEpisodes(seriesId: String): AppResult<List<JellyfinItem>>

    /** `null` when the series is fully watched. */
    suspend fun getNextUpForSeries(seriesId: String): AppResult<JellyfinItem?>

    suspend fun getSimilarItems(
        id: String,
        limit: Int = DEFAULT_SIMILAR_LIMIT,
    ): AppResult<List<JellyfinItem>>

    // ---- end item detail -----------------------------------------------------------------------

    companion object {
        /** Row size jellyfin-web uses for *Continue watching*. */
        const val DEFAULT_RESUME_LIMIT = 12

        /** Row size jellyfin-web uses for *Next up*. */
        const val DEFAULT_NEXT_UP_LIMIT = 24

        /** Row size jellyfin-web uses for the per-library *Latest* rows. */
        const val DEFAULT_LATEST_LIMIT = 16

        /**
         * Above a slow library page, below the SDK's own 30-second socket timeout: a server down
         * while Wi-Fi stays up must degrade without a 30-second hang. Enforced by
         * `DelegatingJellyfinRepository`; public because `:player`'s `PlaybackSourceResolver`
         * reuses it while the implementations stay `internal`.
         */
        const val ONLINE_CALL_TIMEOUT_MS = 10_000L

        const val DEFAULT_TOP_TRACKS_LIMIT = 50

        /** Guards the queue: an artist seed would otherwise hand `setMediaItems` hundreds of tracks. */
        const val DEFAULT_INSTANT_MIX_LIMIT = 200
    }

    // ---- library & search -----------------------------------------------------------------------

    /**
     * Paging configuration belongs to the implementation, not the caller — the offline one pages
     * Room behind its own `Pager`.
     *
     * @param query [ItemQuery.startIndex] and [ItemQuery.limit] are overridden per page.
     * @param onTotalCount reported at most once per load, and only where the source can answer —
     *   the online grid asks the server on its first page, the offline one never does.
     */
    fun getItemsPaged(
        query: ItemQuery,
        onTotalCount: (Int) -> Unit = {},
    ): Flow<PagingData<JellyfinItem>>

    /**
     * Unpaged by design — one capped request (50 results), as jellyfin-web's search does, keeping
     * the debounced typing path to a single in-flight call.
     */
    suspend fun getItems(query: ItemQuery): AppResult<List<JellyfinItem>>

    /**
     * @param parentId library to describe, or `null` for the whole user root.
     * @param itemTypes facets are computed over these kinds only.
     */
    suspend fun getFilterFacets(
        parentId: String?,
        itemTypes: List<ItemType>,
    ): AppResult<FilterFacets>

    // ---- music ---------------------------------------------------------------------------------

    /** Disc/track order: `ParentIndexNumber, IndexNumber, SortName`, the last a stable tiebreak. */
    suspend fun getAlbumTracks(albumId: String): AppResult<List<JellyfinItem>>

    /** Newest first: `ProductionYear desc, PremiereDate desc, SortName`, the last a stable tiebreak. */
    suspend fun getArtistAlbums(artistId: String): AppResult<List<JellyfinItem>>

    /**
     * Online this is the server's own play-count ranking; offline it is a documented approximation
     * over this device's downloaded tracks — see [OfflineJellyfinRepository.getArtistTopTracks].
     */
    suspend fun getArtistTopTracks(
        artistId: String,
        limit: Int = DEFAULT_TOP_TRACKS_LIMIT,
    ): AppResult<List<JellyfinItem>>

    /**
     * Must use `/Playlists/{id}/Items`: a generic `parentId` query does not preserve playlist order.
     * Offline it is always empty — Room has no playlist-membership table, and adding one is the
     * deferred item "offline playlist membership".
     */
    suspend fun getPlaylistItems(playlistId: String): AppResult<List<JellyfinItem>>

    // ---- Continue Listening ---------------------------------------------------------------------

    /** [getResumeItems]'s audio counterpart: the same `Items/Resume` endpoint, `mediaTypes=[AUDIO]`. */
    suspend fun getResumeAudioItems(limit: Int = DEFAULT_RESUME_LIMIT): AppResult<List<JellyfinItem>>

    // ---- Instant Mix & lyrics -------------------------------------------------------------------

    /**
     * A server-generated "radio" queue seeded from [itemId]. Offline it is always
     * [dev.jellyboost.core.common.AppError.Network] — a server recommendation has no local stand-in.
     */
    suspend fun getInstantMix(
        itemId: String,
        limit: Int = DEFAULT_INSTANT_MIX_LIMIT,
    ): AppResult<List<JellyfinItem>>

    /**
     * [dev.jellyboost.core.common.AppError.NotFound] means the server has none — the pane hides the
     * affordance rather than showing an error. Offline: always
     * [dev.jellyboost.core.common.AppError.Network]; lyrics are not cached.
     */
    suspend fun getLyrics(itemId: String): AppResult<Lyrics>
}

// ---- item detail --------------------------------------------------------------------------

const val DEFAULT_SIMILAR_LIMIT = 12
