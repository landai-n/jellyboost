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
 * How a card says, in one breath, what it is.
 *
 * Before the 2026-08-05 accessibility audit a card was three to six separate stops for a screen
 * reader — the artwork (announcing the title), the title (announcing it again), the subtitle, and
 * every badge floating free of the item it belongs to — and none of them carried the state the
 * badges *drew*: how far in you are, whether it is downloaded, whether it is selected (audit CR-6,
 * A11Y-01…05). The fix is one merged node per card with an authored sentence, which means the
 * sentence has to be assembled somewhere, from facts a card holds in five different places.
 *
 * That assembly is here, split in two so the part with the rules in it is a plain function that a
 * JVM test can hold still:
 *
 * - [MediaCardFacts] + [describe] — pure. Order, what is dropped, what is deduplicated.
 * - [mediaCardDescription] — the composable that resolves an item's facts into localized strings
 *   and hands them over.
 *
 * The description deliberately carries the **untruncated** title: the visible one is `maxLines = 1`
 * and ellipsizes after a handful of characters at large font scales, and the artwork description
 * that used to compensate for that is exactly what this replaces (audit SCALE-04).
 */
data class MediaCardFacts(
    /** The item's headline — untruncated, whatever the visible `Text` had room for. */
    val title: String,
    /** "Movie", "Episode", "Library"… `null` for a kind with no useful word (a plain folder). */
    val typeLabel: String? = null,
    /** The card's second line: a year, `S1 · E4 · Episode title`, an item count. */
    val subtitle: String? = null,
    /** The top-left overlay badge — "S1 · E10", "4K". Announced only when there is no [subtitle]. */
    val badge: String? = null,
    /** "45% watched", or the time-left chip's own words when the card draws one. */
    val progressLabel: String? = null,
    /** Rating, download state, watched — in the order the card draws them. */
    val stateLabels: List<String> = emptyList(),
)

/** Separator between the facts — a comma and a space, which is a pause in every screen reader. */
private const val DESCRIPTION_SEPARATOR = ", "

/**
 * Joins facts into the one sentence a merged accessibility node says.
 *
 * Three surfaces assembled a spoken sentence from a handful of nullable facts — a card, the detail
 * header's metadata row, and the home hero's meta line — and all three had to know the same three
 * things. They had drifted into three different answers (audit DUP-8), so the rule is stated once,
 * here:
 *
 * - **A comma and a space between parts.** It is a *pause* in every screen reader, where the row
 *   draws a gap or an interpunct. Neither of those is punctuation a synthesizer honours.
 * - **Blanks are dropped, not spoken.** A fact that is present but empty — a certificate the
 *   server returned as `""` — used to become a bare comma: the home hero announced
 *   "Rated , 22 minutes left", which is the defect this join fixes for it.
 * - **Identical parts collapse.** A card whose subtitle repeats its title says it once.
 */
fun describeParts(parts: List<String?>): String =
    parts
        .mapNotNull { part -> part?.trim()?.takeIf { it.isNotEmpty() } }
        .distinct()
        .joinToString(DESCRIPTION_SEPARATOR)

/** [describeParts] without the list ceremony. */
fun describeParts(vararg parts: String?): String = describeParts(parts.toList())

/**
 * The sentence itself: type, title, subtitle, badge, progress, then state.
 *
 * The one rule of its own, on top of what [describeParts] does: the [MediaCardFacts.badge] is
 * dropped when there is a [MediaCardFacts.subtitle], because the badge is a *shorter* spelling
 * of what the subtitle already said on every card that has both (`S1 · E10` beside
 * `S1 · E4 · The Bicameral Mind`).
 */
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

/**
 * Playback progress as whole percent, which is how it is spoken.
 *
 * Clamped rather than trusted: `playbackProgress` divides a stored position by a runtime the server
 * reported, and a position past the end (a resume point saved after a re-encode shortened the file)
 * would otherwise announce "104% watched".
 */
fun progressPercent(progress: Float): Int = (progress.coerceIn(0f, 1f) * PERCENT).roundToInt()

private const val PERCENT = 100

/**
 * The word for an item's kind, or `null` for kinds a person would not name out loud.
 *
 * Containers ([ItemType.COLLECTION_FOLDER], [ItemType.FOLDER]) and [ItemType.UNKNOWN] return `null`
 * — "Folder, Movies" is noise, and a type the client does not understand has no honest word.
 */
@StringRes
internal fun itemTypeLabelRes(type: ItemType): Int? =
    when (type) {
        ItemType.MOVIE -> R.string.media_card_type_movie
        ItemType.SERIES -> R.string.media_card_type_series
        ItemType.SEASON -> R.string.media_card_type_season
        ItemType.EPISODE -> R.string.media_card_type_episode
        ItemType.COLLECTION_FOLDER, ItemType.FOLDER, ItemType.UNKNOWN -> null
    }

/**
 * What a [DownloadState] is called — the same words the badge's glyph carries, so the card and the
 * badge cannot drift apart. `null` for [DownloadState.NotDownloaded], which draws nothing and has
 * nothing to say.
 */
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
 * Everything [PosterCard] and [ThumbCard] announce, resolved from the item and the overlays the
 * caller asked for.
 *
 * Selection state is deliberately **not** in here: a card in selection mode carries it as real
 * `selected` semantics via [mediaCardSemantics], where a screen reader can announce the toggle
 * rather than hear a sentence that happens to end in the word "Selected".
 *
 * @param timeChipText the card's "22m left" chip, when it draws one. It wins over the percentage:
 *   it is the more concrete statement of the same fact, and it is what the eye reads off the card.
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
            // The localized form, which is the one the card *draws* — before this the card drew
            // "S1 · E4" and spoke "S1:E4" (audit DUP-7).
            subtitle = item.subtitleLine(),
            badge = badge,
            progressLabel =
                timeChipText
                    ?: progress?.let { stringResource(R.string.media_card_progress, progressPercent(it)) },
            stateLabels =
                listOfNotNull(
                    ratingBadge?.let { stringResource(R.string.media_card_rating, formatRatingBadge(it)) },
                    downloadStateLabel(item.downloadState),
                    // Mirrors the tick the artwork draws: mid-item the progress bar has already
                    // said "not finished", so the card does not also claim to be watched.
                    stringResource(R.string.media_card_watched).takeIf { item.userData.played && progress == null },
                ),
        )
    return facts.describe()
}

/**
 * The one node a card is: merged, named, clickable-shaped, and — in selection mode — checkable.
 *
 * `mergeDescendants` is what collapses the artwork, the title, the subtitle and the badges into a
 * single stop. Everything inside a card is therefore silenced individually (the artwork takes a
 * `null` description, the texts and overlay badges `clearAndSetSemantics`), because a merged node
 * *concatenates* its children's descriptions onto its own rather than replacing them — the
 * authored sentence has to be the only thing left to say.
 *
 * @param selected `null` outside selection mode; otherwise real `selected` semantics plus a spoken
 *   state, so the mode is something a screen-reader user can see themselves in (audit M1/A11Y-20).
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
