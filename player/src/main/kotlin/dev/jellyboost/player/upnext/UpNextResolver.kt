package dev.jellyboost.player.upnext

import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.JellyfinRepository
import javax.inject.Inject

/**
 * The episode the player will offer while the current one's ending plays.
 *
 * Deliberately not a `JellyfinItem`: the card draws four things, and handing the whole item to the
 * UI state would put a forty-field object — overview, people, studios, user data — inside a value
 * the player screen diffs on every publish.
 *
 * @property itemId the successor's id, as the server spells it. A string rather than a `UUID`
 *   because that is what `JellyfinItem` carries; the one caller that needs a `UUID` converts at the
 *   point of use (`PlayerViewModel.playNextEpisode`).
 * @property imageUrl the episode's own still — its thumb if the server has one, else its primary
 *   image, which for an episode *is* a still. `null` when it has neither.
 */
internal data class UpNextEpisode(
    val itemId: String,
    val title: String,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val imageUrl: String?,
)

/**
 * Finds what follows the episode that is playing.
 *
 * The recipe mirrors `ItemDetailViewModel.groupPlayQueue`: fetch the item, take its series, list the
 * series' episodes, find this one in the list and take the one after it. Cross-season order comes
 * free — `getSeriesEpisodes` returns the whole series in playing order, so the last episode of a
 * season is followed by the first of the next.
 *
 * ### Positional, not "next up"
 * Deliberately **not** `JellyfinRepository.getNextUpForSeries`, which answers a different question:
 * the next *unwatched* episode. On a rewatch that is somewhere else entirely — often the episode
 * after the point the user last stopped, several seasons away — and a card offering it while
 * episode 3 of a rewatch plays out is offering the wrong thing with full confidence. The successor
 * in the list is what "up next" means to someone watching an episode end.
 *
 * ### Offline
 * `JellyfinRepository` is the delegating one: offline it answers from the downloads, so
 * `getSeriesEpisodes` lists only what is on the device and the successor is the next *downloaded*
 * episode. That is the honest answer — an offline card must not offer something that cannot be
 * played — and it is why nothing here asks whether the app is online.
 *
 * Every miss is `null`, and `null` means "no card": not an episode, no series, a listing that does
 * not contain this item, the last episode of the series, or either call failing. None of it is
 * surfaced to the user — an up-next card is a convenience, and its absence is indistinguishable
 * from an item that simply has no successor.
 */
internal class UpNextResolver
    @Inject
    constructor(
        private val repository: JellyfinRepository,
    ) {
        /** The episode after [itemId], or `null` if there is not one worth offering. */
        suspend fun resolve(itemId: String): UpNextEpisode? {
            val item = repository.getItem(itemId).getOrNull() ?: return null
            val seriesId = item.seriesId?.takeIf { item.type == ItemType.EPISODE } ?: return null

            val episodes = repository.getSeriesEpisodes(seriesId).getOrNull().orEmpty()
            return episodes
                // Matched on the fetched item's own id rather than on the argument: the two spellings
                // of a Jellyfin id (dashed and dash-less) both reach this method, and the listing
                // agrees with the item it came from.
                .indexOfFirst { it.id == item.id }
                // `indexOfFirst` answers -1 for "not in this listing", and -1 + 1 is the *first*
                // episode — the one miss that would produce a confidently wrong card.
                .takeIf { it >= 0 }
                ?.let { episodes.getOrNull(it + 1) }
                ?.asUpNextEpisode()
        }
    }

/** The card's four fields, taken off the successor the listing named. */
private fun JellyfinItem.asUpNextEpisode(): UpNextEpisode =
    UpNextEpisode(
        itemId = id,
        title = name,
        indexNumber = indexNumber,
        parentIndexNumber = parentIndexNumber,
        // The thumb first, exactly as `PlayerViewModel.loadTitleAndArtwork` orders its fallbacks:
        // both surfaces are landscape, and an episode's primary image is a still of the episode.
        imageUrl = thumbImageUrl ?: primaryImageUrl,
    )
