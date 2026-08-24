package dev.jellyboost.player.upnext

import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.JellyfinRepository
import javax.inject.Inject

/** Deliberately not a `JellyfinItem`: this value sits in UI state the player screen diffs on every publish. */
internal data class UpNextEpisode(
    val itemId: String,
    val title: String,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val imageUrl: String?,
)

/**
 * Positional successor, deliberately **not** `getNextUpForSeries`, which answers the next *unwatched* episode —
 * on a rewatch that is seasons away.
 *
 * The repository is the delegating one, so offline this lists only downloaded episodes; nothing here asks
 * whether the app is online. Every miss is `null`, meaning "no card", and none of it is surfaced.
 */
internal class UpNextResolver
    @Inject
    constructor(
        private val repository: JellyfinRepository,
    ) {
        suspend fun resolve(itemId: String): UpNextEpisode? {
            val item = repository.getItem(itemId).getOrNull() ?: return null
            val seriesId = item.seriesId?.takeIf { item.type == ItemType.EPISODE } ?: return null

            val episodes = repository.getSeriesEpisodes(seriesId).getOrNull().orEmpty()
            return episodes
                // Matched on the fetched item's id, not the argument: both spellings of a Jellyfin id (dashed and
                // dash-less) reach this method, and the listing agrees with the item it came from.
                .indexOfFirst { it.id == item.id }
                // -1 means "not in this listing", and -1 + 1 would offer the *first* episode.
                .takeIf { it >= 0 }
                ?.let { episodes.getOrNull(it + 1) }
                ?.asUpNextEpisode()
        }
    }

private fun JellyfinItem.asUpNextEpisode(): UpNextEpisode =
    UpNextEpisode(
        itemId = id,
        title = name,
        indexNumber = indexNumber,
        parentIndexNumber = parentIndexNumber,
        // Thumb first, as `PlayerViewModel.loadTitleAndArtwork` orders it: both surfaces are landscape.
        imageUrl = thumbImageUrl ?: primaryImageUrl,
    )
