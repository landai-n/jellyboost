package dev.jellyboost.core.common.model

import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.Ticks
import java.time.Instant

/**
 * The single item model the UI ever sees: both the online (SDK) and offline (Room) repositories map onto
 * this exact type, and `BaseItemDto`/`ItemEntity` never cross a repository boundary.
 *
 * Image fields are fully-built URL strings — URL construction belongs to the data layer.
 */
data class JellyfinItem(
    val id: String,
    val name: String,
    val type: ItemType,
    val overview: String? = null,
    val productionYear: Int? = null,
    val runTimeTicks: Long? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    /** Episode number within its season, a movie's position in a box set, or a track's position on its disc. */
    val indexNumber: Int? = null,
    /** Season number for an episode. Doubles as a track's disc number. */
    val parentIndexNumber: Int? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    val parentId: String? = null,
    val primaryImageUrl: String? = null,
    val backdropImageUrl: String? = null,
    val thumbImageUrl: String? = null,
    val logoImageUrl: String? = null,
    val primaryImageAspectRatio: Double? = null,
    /**
     * Detail-only, like the five fields below it: populated by the full `getItem` re-fetch the detail screen
     * performs, and left at their defaults by a lean list request.
     */
    val taglines: List<String> = emptyList(),
    /** Season count for a series, episode count for a season. */
    val childCount: Int? = null,
    val premiereDate: Instant? = null,
    val studios: List<String> = emptyList(),
    val people: List<Person> = emptyList(),
    val sizeBytes: Long? = null,
    val userData: UserData = UserData(),
    val downloadState: DownloadState = DownloadState.NotDownloaded,
    /** `false` when the item is known but cannot be opened now; the offline repository sets this, not a throw. */
    val available: Boolean = true,
    val album: String? = null,
    val albumId: String? = null,
    val albumArtist: String? = null,
    val artists: List<String> = emptyList(),
    val artistRefs: List<ArtistRef> = emptyList(),
    /**
     * The media file's container, when the server named one.
     *
     * `/Audio/{id}/universal` decides direct-play-versus-transcode server side and answers with bytes rather
     * than a description, so this is the only fact the client has to infer the `PlayMethod` its reports
     * carry — inferring it here is what avoids a `PlaybackInfo` round trip per track.
     */
    val container: String? = null,
) {
    val displayTitle: String
        get() = if (type == ItemType.EPISODE) seriesName ?: name else name

    /** **Not the drawing surfaces' form — see [episodeLabel] for what still reads this and why.** */
    val displaySubtitle: String?
        get() =
            when (type) {
                ItemType.EPISODE -> listOfNotNull(episodeLabel, name).joinToString(Separators.DOT).ifEmpty { null }
                ItemType.SEASON -> seriesName
                ItemType.AUDIO -> artists.joinToString(", ").ifEmpty { null }
                ItemType.MUSIC_ALBUM ->
                    listOfNotNull(albumArtist, productionYear?.toString()).joinToString(" · ").ifEmpty { null }
                else -> productionYear?.toString()
            }

    /**
     * `S` and `E` are the initials of words, and this builds them from Kotlin string templates — invisible to
     * the `MissingTranslation` gate and untranslatable in a 69-locale app. Every *drawing* surface must go
     * through `:core:ui`'s `episodeNumberLabel()` / `subtitleLine()` instead; this survives as the
     * non-composable fallback, genuinely needed only for the Cast receiver's metadata, which leaves the
     * device. The two Compose readers left (`PlayerUiState.title`, `SyncPlayQueueRow.subtitle`) carry an
     * already-resolved `String` and need reshaping to `UiText` — that reshape is the debt this note records.
     */
    val episodeLabel: String?
        get() =
            when {
                parentIndexNumber != null && indexNumber != null -> "S$parentIndexNumber:E$indexNumber"
                indexNumber != null -> "E$indexNumber"
                else -> null
            }

    val playbackProgress: Float?
        get() = userData.progress(runTimeTicks)

    val runtimeMinutes: Int?
        get() = runTimeTicks?.takeIf { it > 0L }?.let { Ticks.ticksToMinutes(it) }

    val remainingMinutes: Int?
        get() {
            val total = runTimeTicks?.takeIf { it > 0L } ?: return null
            if (!userData.isResumable) return null
            return Ticks.ticksToMinutes((total - userData.playbackPositionTicks).coerceAtLeast(0L))
        }
}
