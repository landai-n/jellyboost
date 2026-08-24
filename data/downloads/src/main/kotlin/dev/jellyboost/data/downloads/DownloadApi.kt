package dev.jellyboost.data.downloads

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.network.runCatchingApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetPlaylistItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The server calls the download pipeline makes, behind a seam so enqueueing is unit-testable. */
internal interface DownloadApi {
    /**
     * Re-fetches items with every field the file plan needs.
     *
     * @return the items the server knew about; ids it did not recognise are simply absent.
     */
    suspend fun getFullItems(ids: List<UUID>): AppResult<List<org.jellyfin.sdk.model.api.BaseItemDto>>

    /**
     * The episodes of a series, or of one of its seasons, in broadcast order.
     *
     * This is what turns "download this season" into downloads: a season and a series are folders,
     * and a folder has no file to fetch. Only the **ids** are returned —
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

    /**
     * An album's tracks, in disc-then-track order.
     *
     * [getEpisodeIds]'s music counterpart, and it exists for the same reason: a `MUSIC_ALBUM` is a
     * folder, so tapping Download on one has to become one download per track. The order matches
     * `OnlineJellyfinRepository.getAlbumTracks` exactly — a queue drained top-to-bottom then
     * downloads the album in the order the user sees it on the album page.
     */
    suspend fun getAlbumTrackIds(albumId: UUID): AppResult<List<UUID>>

    /**
     * Every track of an artist, **grouped by album**.
     *
     * Sorted album-then-disc-then-track rather than by any global key, so a whole-artist download
     * drains one album at a time: the Downloads screen's album groups fill in one by one and each
     * reads as complete or not, instead of every album of the artist sitting half-downloaded at
     * once.
     */
    suspend fun getArtistTrackIds(artistId: UUID): AppResult<List<UUID>>

    /**
     * A playlist's **audio** members, in playlist order.
     *
     * `/Playlists/{id}/Items` rather than a `parentId` items query, for the reason
     * `OnlineJellyfinRepository.getPlaylistItems` gives: a generic items query is not guaranteed to
     * preserve playlist order and the dedicated endpoint is built for exactly that. A mixed
     * playlist's video members are dropped here rather than downloaded — the tap was on a music
     * screen, and expanding it into films is not what it meant.
     */
    suspend fun getPlaylistTrackIds(playlistId: UUID): AppResult<List<UUID>>
}

/**
 * [DownloadApi] on `itemsApi.getItems`, with an explicit field list.
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

        override suspend fun getAlbumTrackIds(albumId: UUID): AppResult<List<UUID>> =
            withContext(ioDispatcher) {
                runCatchingApi {
                    apiClient.itemsApi
                        .getItems(
                            GetItemsRequest(
                                parentId = albumId,
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                recursive = true,
                                sortBy = TRACK_ORDER,
                                sortOrder = listOf(SortOrder.ASCENDING),
                                fields = emptyList(),
                                enableImages = false,
                                enableUserData = false,
                                enableTotalRecordCount = false,
                            ),
                        ).content.items
                        .map { it.id }
                }
            }

        override suspend fun getArtistTrackIds(artistId: UUID): AppResult<List<UUID>> =
            withContext(ioDispatcher) {
                runCatchingApi {
                    apiClient.itemsApi
                        .getItems(
                            GetItemsRequest(
                                // `albumArtistIds`, not `artistIds`: a whole-artist download means
                                // the artist's own records, not every compilation they guest on —
                                // the same predicate `getArtistAlbums` browses by.
                                albumArtistIds = listOf(artistId),
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                recursive = true,
                                sortBy = listOf(ItemSortBy.ALBUM) + TRACK_ORDER,
                                sortOrder = listOf(SortOrder.ASCENDING),
                                fields = emptyList(),
                                enableImages = false,
                                enableUserData = false,
                                enableTotalRecordCount = false,
                            ),
                        ).content.items
                        .map { it.id }
                }
            }

        override suspend fun getPlaylistTrackIds(playlistId: UUID): AppResult<List<UUID>> =
            withContext(ioDispatcher) {
                runCatchingApi {
                    apiClient.playlistsApi
                        .getPlaylistItems(
                            GetPlaylistItemsRequest(
                                playlistId = playlistId,
                                fields = emptyList(),
                                enableImages = false,
                                enableUserData = false,
                            ),
                        ).content.items
                        .filter { it.type == BaseItemKind.AUDIO }
                        .map { it.id }
                }
            }

        private companion object {
            /**
             * Disc, then track, then a stable alphabetical tiebreak — the album page's own order
             * (`OnlineJellyfinRepository.getAlbumTracks`).
             */
            val TRACK_ORDER =
                listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME)

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
