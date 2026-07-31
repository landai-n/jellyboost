package dev.jellyboost.data.downloads

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.di.IoDispatcher
import dev.jellyboost.data.runCatchingApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The server calls the download pipeline makes, behind a seam so enqueueing is unit-testable. */
interface DownloadApi {
    /**
     * Re-fetches items with every field the file plan needs.
     *
     * @return the items the server knew about; ids it did not recognise are simply absent.
     */
    suspend fun getFullItems(ids: List<UUID>): AppResult<List<org.jellyfin.sdk.model.api.BaseItemDto>>

    /**
     * The episodes of a series, or of one of its seasons, in broadcast order.
     *
     * This is what turns "download this season" into downloads (DECISIONS.md, 2026-07-29): a season
     * and a series are folders, and a folder has no file to fetch. Only the **ids** are returned —
     * [getFullItems] then fetches the same rich DTOs a single-episode download uses, so an expanded
     * episode is byte-for-byte the row a direct tap on that episode would have produced.
     *
     * @param seasonId `null` for every episode of the series, across all its seasons.
     * @return ids in the order the server lists them; empty when the container has no episodes.
     */
    suspend fun getEpisodeIds(
        seriesId: UUID,
        seasonId: UUID?,
    ): AppResult<List<UUID>>
}

/**
 * [DownloadApi] on `itemsApi.getItems`, with the plan's field list (docs/PLAN.md, "Download
 * pipeline" → Enqueue).
 *
 * `getItems(ids = …)` rather than the per-item `getItem` endpoint because an episode is fetched
 * together with its series and season — one request instead of three, and the field list is
 * explicit rather than "whatever that endpoint happens to return".
 */
@Singleton
internal class SdkDownloadApi
    @Inject
    constructor(
        private val apiClient: ApiClient,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DownloadApi {
        override suspend fun getFullItems(ids: List<UUID>): AppResult<List<org.jellyfin.sdk.model.api.BaseItemDto>> =
            withContext(ioDispatcher) {
                runCatchingApi {
                    apiClient.itemsApi
                        .getItems(
                            GetItemsRequest(
                                ids = ids,
                                fields = DOWNLOAD_FIELDS,
                                enableUserData = true,
                                enableTotalRecordCount = false,
                            ),
                        ).content.items
                }
            }

        /**
         * `/Shows/{seriesId}/Episodes`, the same endpoint the season detail page reads.
         *
         * No `fields`, no images and no user data: the caller wants ids, and asking the server for
         * the full DTO of every episode of a series only to throw all but the id away would make
         * "download this show" a much heavier request than it needs to be. `isMissing = false` for
         * the reason the browse path gives it — an episode with no file on the server is nothing a
         * download could ever fetch.
         */
        override suspend fun getEpisodeIds(
            seriesId: UUID,
            seasonId: UUID?,
        ): AppResult<List<UUID>> =
            withContext(ioDispatcher) {
                runCatchingApi {
                    apiClient.tvShowsApi
                        .getEpisodes(
                            GetEpisodesRequest(
                                seriesId = seriesId,
                                seasonId = seasonId,
                                fields = emptyList(),
                                enableImages = false,
                                enableUserData = false,
                                isMissing = false,
                            ),
                        ).content.items
                        .map { it.id }
                }
            }

        private companion object {
            /**
             * Everything the file plan and the offline detail page read.
             *
             * `MEDIA_SOURCES` and `MEDIA_STREAMS` drive the media file and the subtitle list;
             * `TRICKPLAY` the tile sheets; `PATH` the filename on disk; the rest is what an offline
             * detail page has to render without a server to ask.
             */
            val DOWNLOAD_FIELDS =
                listOf(
                    ItemFields.MEDIA_SOURCES,
                    ItemFields.MEDIA_STREAMS,
                    ItemFields.PATH,
                    ItemFields.OVERVIEW,
                    ItemFields.GENRES,
                    ItemFields.CHAPTERS,
                    ItemFields.TRICKPLAY,
                    ItemFields.PEOPLE,
                    ItemFields.STUDIOS,
                    ItemFields.TAGLINES,
                    ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                )
        }
    }
