package dev.jellyboost.data.music

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LyricDto
import java.util.UUID

/**
 * `InstantMixApi`/`LyricsApi` behind one seam.
 *
 * The `PlayerApi`/`SdkPlayerApi` pattern (`:player/api/PlayerApi.kt`): the SDK exposes its
 * operation groups as extension properties on the abstract `ApiClient`, which is awkward to fake
 * directly, so the two calls needed here sit behind a small mockable interface instead.
 * Raw SDK DTOs at this layer — `OnlineJellyfinRepository` is where a [BaseItemDto]/[LyricDto]
 * becomes a domain model, the same split every other `:data` read follows.
 */
interface MusicApi {
    /**
     * `/Items/{itemId}/InstantMix` — a server-generated "radio" queue seeded from one item.
     *
     * The generic from-item endpoint, not one of `InstantMixApi`'s from-album/from-artist/
     * from-song variants: the server dispatches on the seed's own kind, so this one call shape
     * covers every "Start radio" call site (album, artist, track, now-playing).
     *
     * @param limit caps the mix — a whole-artist seed could otherwise hand the queue a very large
     *   playlist; [dev.jellyboost.data.JellyfinRepository.DEFAULT_INSTANT_MIX_LIMIT] is the
     *   default a caller gets if it does not ask for a different one.
     */
    suspend fun getInstantMix(
        itemId: UUID,
        limit: Int,
    ): List<BaseItemDto>

    /**
     * `/Audio/{itemId}/Lyrics` — the track's lyrics, synced or plain.
     *
     * Throws (via the SDK's `InvalidStatusException`, folded onto [dev.jellyboost.core.common.AppError.NotFound]
     * by [dev.jellyboost.data.runCatchingApi]) when the server has none for this item — there is no
     * "empty lyrics" success shape to check for instead.
     */
    suspend fun getLyrics(itemId: UUID): LyricDto
}
