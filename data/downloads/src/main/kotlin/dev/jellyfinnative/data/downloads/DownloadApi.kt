package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.data.runCatchingApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The one server call the download pipeline makes, behind a seam so enqueueing is unit-testable. */
interface DownloadApi {
    /**
     * Re-fetches items with every field the file plan needs.
     *
     * @return the items the server knew about; ids it did not recognise are simply absent.
     */
    suspend fun getFullItems(ids: List<UUID>): AppResult<List<org.jellyfin.sdk.model.api.BaseItemDto>>
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
