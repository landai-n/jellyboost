package dev.jellyboost.core.common.model

import java.time.Instant

/**
 * The single item model the UI ever sees.
 *
 * Both the online (SDK) and the offline (Room) repositories map onto this exact type — that
 * identity is the mechanism behind the one seamless online/offline UI (docs/PLAN.md,
 * "Data layer"). `BaseItemDto` and `ItemEntity` never cross a repository boundary.
 *
 * Image fields are fully-built URL strings: URL construction (server base URL, image tags,
 * sizing) belongs to the data layer, so `:core:ui` only has to hand a string to Coil.
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
    /** Episode number within its season, or a movie's position in a box set. */
    val indexNumber: Int? = null,
    /** Season number for an episode. */
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
     * Short marketing line(s) shown under the title on a detail page.
     *
     * Detail-only (M4), like the four fields below it: they are populated by the full `getItem`
     * re-fetch the detail screen performs, and a lean list request leaves them at their defaults
     * (docs/PLAN.md, "Screens" → ItemDetail).
     */
    val taglines: List<String> = emptyList(),
    /** Season count for a series, episode count for a season. Detail-only. */
    val childCount: Int? = null,
    /** Original release date. Detail-only. */
    val premiereDate: Instant? = null,
    /** Production companies. Detail-only. */
    val studios: List<String> = emptyList(),
    /** Cast and crew. Detail-only. */
    val people: List<Person> = emptyList(),
    /** Media file size in bytes, from the item's first media source. Detail-only. */
    val sizeBytes: Long? = null,
    val userData: UserData = UserData(),
    val downloadState: DownloadState = DownloadState.NotDownloaded,
    /**
     * `false` when the item is known (e.g. cached as the parent of a download) but cannot be
     * opened right now. The offline repository sets this instead of throwing.
     */
    val available: Boolean = true,
) {
    /**
     * Headline shown on a card: episodes lead with the series they belong to, everything else
     * uses its own name.
     */
    val displayTitle: String
        get() = if (type == ItemType.EPISODE) seriesName ?: name else name

    /**
     * Second line on a card: `S1:E4 · Episode title` for episodes, the production year otherwise.
     */
    val displaySubtitle: String?
        get() =
            when (type) {
                ItemType.EPISODE -> listOfNotNull(episodeLabel, name).joinToString(" · ").ifEmpty { null }
                ItemType.SEASON -> seriesName
                else -> productionYear?.toString()
            }

    /** Compact `S1:E4` label, or `null` when the season/episode numbers are unknown. */
    val episodeLabel: String?
        get() =
            when {
                parentIndexNumber != null && indexNumber != null -> "S$parentIndexNumber:E$indexNumber"
                indexNumber != null -> "E$indexNumber"
                else -> null
            }

    /** Playback progress in `0f..1f`, or `null` when the item was never started. */
    val playbackProgress: Float?
        get() = userData.progress(runTimeTicks)

    /** Runtime rounded to whole minutes, or `null` when the server reports no runtime. */
    val runtimeMinutes: Int?
        get() = runTimeTicks?.takeIf { it > 0L }?.let { (it / TICKS_PER_MINUTE).toInt() }

    /** Remaining runtime in whole minutes for a partially-watched item, `null` otherwise. */
    val remainingMinutes: Int?
        get() {
            val total = runTimeTicks?.takeIf { it > 0L } ?: return null
            if (!userData.isResumable) return null
            return ((total - userData.playbackPositionTicks).coerceAtLeast(0L) / TICKS_PER_MINUTE).toInt()
        }

    private companion object {
        /** Jellyfin measures durations in 100-nanosecond ticks. */
        const val TICKS_PER_MINUTE = 600_000_000L
    }
}
