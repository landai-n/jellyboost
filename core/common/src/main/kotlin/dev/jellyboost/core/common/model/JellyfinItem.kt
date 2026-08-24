package dev.jellyboost.core.common.model

import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.Ticks
import java.time.Instant

/**
 * The single item model the UI ever sees.
 *
 * Both the online (SDK) and the offline (Room) repositories map onto this exact type — that
 * identity is the mechanism behind the one seamless online/offline UI. `BaseItemDto` and
 * `ItemEntity` never cross a repository boundary.
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
    /**
     * Episode number within its season, or a movie's position in a box set. Doubles as a track's
     * position within its disc — Jellyfin numbers tracks the same way it numbers episodes.
     */
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
     * Short marketing line(s) shown under the title on a detail page.
     *
     * Detail-only, like the four fields below it: they are populated by the full `getItem`
     * re-fetch the detail screen performs, and a lean list request leaves them at their defaults.
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
    /** The album a track belongs to. Music only. */
    val album: String? = null,
    /** Id of the album a track belongs to. Music only. */
    val albumId: String? = null,
    /** The album's artist, as display text. Music only. */
    val albumArtist: String? = null,
    /** Every performing artist, as display text — a track or album may credit more than one. */
    val artists: List<String> = emptyList(),
    /** Navigable references to the performing artists, for tapping through to an artist page. */
    val artistRefs: List<ArtistRef> = emptyList(),
    /**
     * The media file's container (`flac`, `mp3`, `m4a` …), when the server named one.
     *
     * Exists for one purpose: the music queue streams through `/Audio/{id}/universal`,
     * which decides direct-play-versus-transcode server side from the container list the client
     * sends, and answers with bytes rather than with a description of what it decided. This is the
     * only fact the client has to infer the `PlayMethod` its reports carry, and inferring it here
     * is what avoids a `PlaybackInfo` round trip per track — the property the whole queue design
     * rests on (`MusicStreamResolver`). `null` on anything the server did not describe.
     */
    val container: String? = null,
) {
    /**
     * Headline shown on a card: episodes lead with the series they belong to, everything else
     * uses its own name.
     */
    val displayTitle: String
        get() = if (type == ItemType.EPISODE) seriesName ?: name else name

    /**
     * Second line on a card: `S1:E4 · Episode title` for episodes, the production year otherwise.
     * A track's subtitle is its performing artists; an album's is `albumArtist · year`.
     *
     * **Not the drawing surfaces' form — see [episodeLabel] for what still reads this and why.**
     */
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
     * Compact `S1:E4` label, or `null` when the season/episode numbers are unknown.
     *
     * ### Why this and [displaySubtitle] are still here
     * `S` and `E` are the initials of words, and these build them from Kotlin string templates —
     * invisible to the `MissingTranslation` gate, and untranslatable in a 69-locale app. Every
     * *drawing* surface therefore goes through `:core:ui`'s `JellyfinItem.episodeNumberLabel()` /
     * `subtitleLine()`, which read the `media_episode_label` resources instead. These
     * two survive as the non-composable fallback, and are read from exactly three places:
     *
     * - `PlayerViewModel.loadTitleAndArtwork` → `CastMetadata.subtitle`, which becomes the Cast
     *   receiver's `MediaMetadata.KEY_SUBTITLE` (`CastMediaItemConverter`). **Genuinely
     *   composition-free** — the string leaves the device.
     * - `PlayerViewModel.loadTitleAndArtwork` → the join into `PlayerUiState.title`, and
     *   - `SyncPlayQueueViewModel.toUiState` → `SyncPlayQueueRow.subtitle`.
     *
     *   Those last two *are* drawn by Compose (`PlayerControls.asTitleAndSubtitle`, the SyncPlay
     *   queue sheet), so the localized form is reachable in principle — but only by reshaping the
     *   two UI states, since both currently carry an already-resolved `String`. Doing it by
     *   injecting a `Context` into the ViewModels instead was considered and rejected: no ViewModel
     *   in this app takes one, and `UiText` exists precisely so they need not (`:core:ui`'s
     *   `UiText`, and `ItemDetailUiState.UserMessage`'s "a type rather than a string so the
     *   ViewModel stays free of resources"). The reshape is the follow-up; this note is the debt.
     */
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
        get() = runTimeTicks?.takeIf { it > 0L }?.let { Ticks.ticksToMinutes(it) }

    /** Remaining runtime in whole minutes for a partially-watched item, `null` otherwise. */
    val remainingMinutes: Int?
        get() {
            val total = runTimeTicks?.takeIf { it > 0L } ?: return null
            if (!userData.isResumable) return null
            return Ticks.ticksToMinutes((total - userData.playbackPositionTicks).coerceAtLeast(0L))
        }
}
