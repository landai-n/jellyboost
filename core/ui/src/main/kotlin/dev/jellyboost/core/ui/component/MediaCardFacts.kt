package dev.jellyboost.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.text.subtitleLine
import kotlin.math.roundToInt

/**
 * The facts a card's one merged accessibility node speaks, kept pure so a JVM test can pin the
 * ordering rules. [title] is deliberately the **untruncated** one — the visible `Text` is
 * `maxLines = 1` and the artwork's own `contentDescription` stays `null` on a titled card.
 */
data class MediaCardFacts(
    val title: String,
    val typeLabel: String? = null,
    val subtitle: String? = null,
    /** Announced only when there is no [subtitle]. */
    val badge: String? = null,
    val progressLabel: String? = null,
    val stateLabels: List<String> = emptyList(),
)

/** A comma and a space, which is a *pause* in every screen reader — an interpunct is not. */
private const val DESCRIPTION_SEPARATOR = ", "

/**
 * The one join every spoken sentence in the app uses (cards, the detail metadata row, the home
 * hero's meta line): blanks are dropped rather than spoken as a bare comma ("Rated , 22 minutes
 * left" is what a server's `""` certificate produced), and identical parts collapse.
 */
fun describeParts(parts: List<String?>): String =
    parts
        .mapNotNull { part -> part?.trim()?.takeIf { it.isNotEmpty() } }
        .distinct()
        .joinToString(DESCRIPTION_SEPARATOR)

fun describeParts(vararg parts: String?): String = describeParts(parts.toList())

/** The badge is dropped when a subtitle exists: it is a shorter spelling of the same fact. */
fun MediaCardFacts.describe(): String =
    describeParts(
        buildList {
            add(typeLabel)
            add(title)
            add(subtitle)
            if (subtitle.isNullOrBlank()) add(badge)
            add(progressLabel)
            addAll(stateLabels)
        },
    )

/** Clamped, not trusted: a resume point past a re-encoded file's runtime announces "104% watched". */
fun progressPercent(progress: Float): Int = (progress.coerceIn(0f, 1f) * PERCENT).roundToInt()

private const val PERCENT = 100

/** `null` for containers and unknown kinds — "Folder, Movies" is noise. */
@StringRes
internal fun itemTypeLabelRes(type: ItemType): Int? =
    when (type) {
        ItemType.MOVIE -> R.string.media_card_type_movie
        ItemType.SERIES -> R.string.media_card_type_series
        ItemType.SEASON -> R.string.media_card_type_season
        ItemType.EPISODE -> R.string.media_card_type_episode
        ItemType.AUDIO -> R.string.media_card_type_song
        ItemType.MUSIC_ALBUM -> R.string.media_card_type_album
        ItemType.MUSIC_ARTIST -> R.string.media_card_type_artist
        ItemType.PLAYLIST -> R.string.media_card_type_playlist
        ItemType.COLLECTION_FOLDER, ItemType.FOLDER, ItemType.UNKNOWN -> null
    }

/** The same words the badge's glyph carries, so a card and its badge cannot drift apart. */
@Composable
fun downloadStateLabel(state: DownloadState): String? =
    when (state) {
        is DownloadState.NotDownloaded -> null
        is DownloadState.Queued -> stringResource(R.string.badge_download_queued)
        is DownloadState.Paused -> stringResource(R.string.badge_download_paused)
        is DownloadState.Downloaded -> stringResource(R.string.badge_downloaded)
        is DownloadState.Failed -> stringResource(R.string.badge_download_failed)
        is DownloadState.Downloading ->
            stringResource(R.string.badge_downloading_progress, progressPercent(state.progress))
    }

/**
 * Selection state is deliberately not here: [mediaCardSemantics] carries it as real `selected`
 * semantics a screen reader announces as a toggle, not as a sentence ending in "Selected".
 */
@Composable
internal fun mediaCardDescription(
    item: JellyfinItem,
    badge: String? = null,
    timeChipText: String? = null,
    ratingBadge: Float? = null,
): String {
    val progress = item.playbackProgress
    val facts =
        MediaCardFacts(
            title = item.displayTitle,
            typeLabel = itemTypeLabelRes(item.type)?.let { stringResource(it) },
            subtitle = item.subtitleLine(),
            badge = badge,
            progressLabel =
                timeChipText
                    ?: progress?.let { stringResource(R.string.media_card_progress, progressPercent(it)) },
            stateLabels =
                listOfNotNull(
                    ratingBadge?.let { stringResource(R.string.media_card_rating, formatRatingBadge(it)) },
                    downloadStateLabel(item.downloadState),
                    // Mirrors the tick the artwork draws — mid-item the progress bar says it instead.
                    stringResource(R.string.media_card_watched).takeIf { item.userData.played && progress == null },
                ),
        )
    return facts.describe()
}

/**
 * A merged node *concatenates* its children's descriptions onto its own rather than replacing them,
 * so everything inside a card must stay individually silenced (`null` artwork description,
 * `clearAndSetSemantics` on the texts and badges) for the authored sentence to be all that is said.
 */
@Composable
fun mediaCardSemantics(
    description: String,
    selected: Boolean? = null,
): Modifier {
    val selectedState = stringResource(R.string.selection_item_selected)
    val unselectedState = stringResource(R.string.selection_item_not_selected)
    return Modifier.semantics(mergeDescendants = true) {
        contentDescription = description
        role = Role.Button
        if (selected != null) {
            this.selected = selected
            stateDescription = if (selected) selectedState else unselectedState
        }
    }
}
