package dev.jellyboost.data.music

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LyricDto
import java.util.UUID

/**
 * The SDK exposes its operation groups as extension properties on the abstract `ApiClient`, which is
 * awkward to fake, so these two calls sit behind a mockable interface (the `PlayerApi` pattern).
 * Raw SDK DTOs at this layer; `OnlineJellyfinRepository` maps them.
 */
interface MusicApi {
    /**
     * `/Items/{itemId}/InstantMix` — the *generic* from-item endpoint, not the from-album/artist/
     * song variants: the server dispatches on the seed's own kind, so one call shape covers every
     * "Start radio" call site.
     */
    suspend fun getInstantMix(
        itemId: UUID,
        limit: Int,
    ): List<BaseItemDto>

    /**
     * **Throws** when the server has none for this item (SDK `InvalidStatusException`, folded onto
     * `AppError.NotFound`) — there is no "empty lyrics" success shape to check for instead.
     */
    suspend fun getLyrics(itemId: UUID): LyricDto
}
