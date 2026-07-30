package dev.jellyfinnative.data

import androidx.paging.PagingData
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.FilterFacets
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.data.JellyfinRepository.Companion.ONLINE_CALL_TIMEOUT_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [JellyfinRepository] the whole app actually injects: it picks between the online and the
 * offline implementation **per call** (docs/PLAN.md, "Data layer").
 *
 * There is no "offline mode" the app enters. Every call asks the same question — is
 * `ConnectionState` online right now? — and takes the corresponding path, which is what lets the
 * user walk out of Wi-Fi mid-scroll and simply keep browsing what they downloaded.
 *
 * ### Failure handling
 * | server answer | what happens |
 * |---|---|
 * | success | returned as is |
 * | transport failure (IO, timeout, TLS) | [ConnectionStateProvider.reportFailure] + this call is retried offline |
 * | 502 / 503 / 504 | same — a proxy in front of a stopped server looks exactly like a dead server |
 * | 401 / 403 | **surfaced unchanged** so the session layer can re-authenticate |
 * | any other server error | surfaced unchanged |
 *
 * Swallowing a 401 into an offline fallback would be the worst possible outcome: the user would
 * silently see only downloaded media while their session quietly stayed expired.
 *
 * ### Not hanging
 * Every online call is bounded by [ONLINE_CALL_TIMEOUT_MS]. The plan's mechanism against "a 30s
 * socket timeout" is the 3-second reachability probe, but the probe can only demote a server it
 * has been given a chance to test; a call already in flight when the server dies would still sit
 * on the SDK's own timeout. The ceiling closes that window — and, like a transport failure, it
 * reports the failure so the probe runs.
 */
@Singleton
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

        /**
         * The paged grid is a stream, so the choice is re-made whenever the connection changes
         * rather than once at subscription: losing the network mid-scroll swaps the grid over to
         * the downloaded items instead of freezing on a failed page.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun getItemsPaged(query: ItemQuery): Flow<PagingData<JellyfinItem>> =
            connectionState.state
                .map { it.isOnline }
                .distinctUntilChanged()
                .flatMapLatest { isOnline ->
                    if (isOnline) online.getItemsPaged(query) else offline.getItemsPaged(query)
                }

        /**
         * @param onlineCall the call to make against the server.
         * @param offlineCall the same call against Room — used when offline, and as the fallback
         *   when the server turned out to be unreachable after all.
         */
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
 * `true` for the failures that mean "the server is not there", as opposed to "the server said no".
 *
 * The 5xx set is deliberately narrow: 502/503/504 are what a reverse proxy returns for a backend
 * that is down or restarting, which is indistinguishable from an unreachable server from the app's
 * point of view. A 500 is the server answering — badly, but answering — and falling back to the
 * cache would hide a real bug behind a stale list.
 */
private fun AppError.isTransportFailure(): Boolean =
    when (this) {
        is AppError.Network -> true
        is AppError.Server -> statusCode in UNREACHABLE_STATUS_CODES
        else -> false
    }

private val UNREACHABLE_STATUS_CODES = setOf(502, 503, 504)
