package dev.jellyboost.data

import androidx.paging.PagingData
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.data.JellyfinRepository.Companion.ONLINE_CALL_TIMEOUT_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks between the online and offline implementation **per call** — there is no "offline mode" the
 * app enters.
 *
 * | server answer | what happens |
 * |---|---|
 * | success | returned as is |
 * | transport failure (IO, timeout, TLS) | [ConnectionStateProvider.reportFailure] + retried offline |
 * | 502 / 503 / 504 | same — a proxy in front of a stopped server looks like a dead server |
 * | 401 / 403 | **surfaced unchanged** so the session layer can re-authenticate |
 * | any other server error | surfaced unchanged |
 *
 * Swallowing a 401 into an offline fallback would silently show downloads-only while the session
 * stayed expired.
 *
 * [ONLINE_CALL_TIMEOUT_MS] bounds every online call: the reachability probe can only demote a server
 * it has been given a chance to test, so a call already in flight when the server dies would
 * otherwise sit on the SDK's own 30-second timeout.
 */
@Singleton
@Suppress(
    "TooManyFunctions",
)
internal class DelegatingJellyfinRepository
    @Inject
    constructor(
        private val online: OnlineJellyfinRepository,
        private val offline: OfflineJellyfinRepository,
        private val connectionState: ConnectionStateProvider,
    ) : JellyfinRepository {
        override suspend fun getUserViews(): AppResult<List<LibraryView>> =
            delegate({ getUserViews() }, { getUserViews() })

        override suspend fun getResumeItems(limit: Int): AppResult<List<JellyfinItem>> =
            delegate({ getResumeItems(limit) }, { getResumeItems(limit) })

        override suspend fun getNextUp(limit: Int): AppResult<List<JellyfinItem>> =
            delegate({ getNextUp(limit) }, { getNextUp(limit) })

        override suspend fun getLatestMedia(
            parentId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            delegate({ getLatestMedia(parentId, limit) }, { getLatestMedia(parentId, limit) })

        override suspend fun getItem(id: String): AppResult<JellyfinItem> = delegate({ getItem(id) }, { getItem(id) })

        override suspend fun getSeasons(seriesId: String): AppResult<List<JellyfinItem>> =
            delegate({ getSeasons(seriesId) }, { getSeasons(seriesId) })

        override suspend fun getEpisodes(
            seriesId: String,
            seasonId: String,
        ): AppResult<List<JellyfinItem>> =
            delegate({ getEpisodes(seriesId, seasonId) }, { getEpisodes(seriesId, seasonId) })

        override suspend fun getSeriesEpisodes(seriesId: String): AppResult<List<JellyfinItem>> =
            delegate({ getSeriesEpisodes(seriesId) }, { getSeriesEpisodes(seriesId) })

        override suspend fun getNextUpForSeries(seriesId: String): AppResult<JellyfinItem?> =
            delegate({ getNextUpForSeries(seriesId) }, { getNextUpForSeries(seriesId) })

        override suspend fun getSimilarItems(
            id: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> = delegate({ getSimilarItems(id, limit) }, { getSimilarItems(id, limit) })

        override suspend fun getItems(query: ItemQuery): AppResult<List<JellyfinItem>> =
            delegate({ getItems(query) }, { getItems(query) })

        override suspend fun getFilterFacets(
            parentId: String?,
            itemTypes: List<ItemType>,
        ): AppResult<FilterFacets> =
            delegate({ getFilterFacets(parentId, itemTypes) }, { getFilterFacets(parentId, itemTypes) })

        // ---- music -----------------------------------------------------------------------------

        override suspend fun getAlbumTracks(albumId: String): AppResult<List<JellyfinItem>> =
            delegate({ getAlbumTracks(albumId) }, { getAlbumTracks(albumId) })

        override suspend fun getArtistAlbums(artistId: String): AppResult<List<JellyfinItem>> =
            delegate({ getArtistAlbums(artistId) }, { getArtistAlbums(artistId) })

        override suspend fun getArtistTopTracks(
            artistId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            delegate({ getArtistTopTracks(artistId, limit) }, { getArtistTopTracks(artistId, limit) })

        override suspend fun getPlaylistItems(playlistId: String): AppResult<List<JellyfinItem>> =
            delegate({ getPlaylistItems(playlistId) }, { getPlaylistItems(playlistId) })

        // ---- Continue Listening -------------------------------------------------------------

        override suspend fun getResumeAudioItems(limit: Int): AppResult<List<JellyfinItem>> =
            delegate({ getResumeAudioItems(limit) }, { getResumeAudioItems(limit) })

        // ---- Instant Mix & lyrics -------------------------------------------------------------

        override suspend fun getInstantMix(
            itemId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> = delegate({ getInstantMix(itemId, limit) }, { getInstantMix(itemId, limit) })

        override suspend fun getLyrics(itemId: String): AppResult<Lyrics> =
            delegate({ getLyrics(itemId) }, { getLyrics(itemId) })

        /**
         * A stream, so the source is re-picked on every connection change rather than once at
         * subscription: losing the network mid-scroll swaps to downloads instead of a failed page.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun getItemsPaged(
            query: ItemQuery,
            onTotalCount: (Int) -> Unit,
        ): Flow<PagingData<JellyfinItem>> =
            connectionState.state
                .map { it.isOnline }
                .distinctUntilChanged()
                .flatMapLatest { isOnline ->
                    if (isOnline) {
                        online.getItemsPaged(query, onTotalCount)
                    } else {
                        offline.getItemsPaged(query, onTotalCount)
                    }
                }

        private suspend fun <T> delegate(
            onlineCall: suspend JellyfinRepository.() -> AppResult<T>,
            offlineCall: suspend JellyfinRepository.() -> AppResult<T>,
        ): AppResult<T> {
            if (!connectionState.state.value.isOnline) return offline.offlineCall()

            val result =
                withTimeoutOrNull(ONLINE_CALL_TIMEOUT_MS) { online.onlineCall() }
                    ?: run {
                        Timber.w("Server call exceeded %d ms; falling back to the cache", ONLINE_CALL_TIMEOUT_MS)
                        return fallBackOffline(offlineCall)
                    }

            return when (result) {
                is AppResult.Success -> result
                is AppResult.Failure ->
                    if (result.error.isTransportFailure()) {
                        fallBackOffline(offlineCall)
                    } else {
                        result
                    }
            }
        }

        private suspend fun <T> fallBackOffline(
            offlineCall: suspend JellyfinRepository.() -> AppResult<T>,
        ): AppResult<T> {
            connectionState.reportFailure()
            return offline.offlineCall()
        }
    }

/**
 * "The server is not there", as opposed to "the server said no". The 5xx set is deliberately narrow:
 * 502/503/504 are what a proxy returns for a backend that is down, while a 500 is the server
 * answering — badly — and falling back would hide a real bug behind a stale list.
 */
private fun AppError.isTransportFailure(): Boolean =
    when (this) {
        is AppError.Network -> true
        is AppError.Server -> statusCode in UNREACHABLE_STATUS_CODES
        else -> false
    }

private val UNREACHABLE_STATUS_CODES =
    setOf(
        HttpURLConnection.HTTP_BAD_GATEWAY,
        HttpURLConnection.HTTP_UNAVAILABLE,
        HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
    )
