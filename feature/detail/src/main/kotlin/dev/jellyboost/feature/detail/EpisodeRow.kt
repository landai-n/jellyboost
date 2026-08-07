package dev.jellyboost.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.component.selectableCardClick
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * One episode in a season's list, drawn as the refresh's surface card (spec section 4c): the 16:9
 * [ThumbCard] artwork — with its played tick, progress bar, download badge and selection
 * indicator — beside the episode's number line, title and synopsis.
 *
 * Reusing `ThumbCard` for the artwork is deliberate — the watched indicator and download badge an
 * episode shows here have to be the exact same ones the home rows show.
 *
 * @param onClick opens the episode's own detail page — or, while the list is in batch-selection
 *   mode, toggles this row (the caller decides; see `ItemDetailScreen`).
 * @param onPlay starts playback directly. Worth its own button: from a season page the thing a
 *   user wants is almost always "play this one", and making them go through the episode page
 *   first adds a screen and a request for nothing. Replaced by the selection checkbox while the
 *   mode is on: play is a one-item action, and the mode is about many.
 * @param onLongClick enters batch-selection mode; `null` on lists that do not offer it.
 * @param selected `null` outside selection mode. A selected card takes an accent wash and edge —
 *   a list row's identity is its text, and dimming the thumbnail alone would not read at a glance
 *   down a column of forty.
 * @param strip the wide layout's card: a fixed-width tile for the horizontal episode strip a
 *   tablet shows, instead of the full-width stacked card a phone scrolls through. Defaults to
 *   `false`, which is the phone shape.
 */
@Composable
internal fun EpisodeRow(
    episode: JellyfinItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    strip: Boolean = false,
) {
    if (strip) {
        EpisodeStripCard(
            episode = episode,
            onClick = onClick,
            onPlay = onPlay,
            onLongClick = onLongClick,
            selected = selected,
            modifier = modifier,
        )
    } else {
        EpisodeStackedCard(
            episode = episode,
            onClick = onClick,
            onPlay = onPlay,
            onLongClick = onLongClick,
            selected = selected,
            modifier = modifier,
        )
    }
}

/** The phone shape: a full-width card, artwork at the start, text filling the rest. */
@Composable
private fun EpisodeStackedCard(
    episode: JellyfinItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: (() -> Unit)?,
    selected: Boolean?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = DetailEdgePadding)
                .episodeCard(selected = selected, onClick = onClick, onLongClick = onLongClick),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        ThumbCard(
            item = episode,
            onClick = onClick,
            width = STACKED_ART_WIDTH,
            showTitle = false,
            onLongClick = onLongClick,
            selected = selected,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
        ) {
            EpisodeNumberLine(episode = episode)
            EpisodeTitle(episode = episode)
            EpisodeOverview(episode = episode)
        }

        EpisodeControl(
            onPlay = onPlay,
            selected = selected,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

/** The tablet shape: a fixed-width tile the wide layout scrolls horizontally. */
@Composable
private fun EpisodeStripCard(
    episode: JellyfinItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: (() -> Unit)?,
    selected: Boolean?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .width(STRIP_CARD_WIDTH)
                .episodeCard(selected = selected, onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
            ThumbCard(
                item = episode,
                onClick = onClick,
                width = STRIP_ART_WIDTH,
                showTitle = false,
                onLongClick = onLongClick,
                selected = selected,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
            ) {
                EpisodeNumberLine(episode = episode)
                EpisodeTitle(episode = episode)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                EpisodeOverview(episode = episode)
            }
            EpisodeControl(onPlay = onPlay, selected = selected)
        }
    }
}

/**
 * The surface both shapes sit on: a `#202020` panel with the refresh's faint hairline, or an accent
 * wash and edge while the card is selected.
 *
 * The click handling lives here too so the whole card — not only its artwork — keeps opening the
 * episode and long-pressing into selection mode, exactly as the pre-refresh row did.
 */
@Composable
private fun Modifier.episodeCard(
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val shape = RoundedCornerShape(Dimens.PanelRadius)
    val fill =
        if (selected == true) {
            MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_FILL_ALPHA)
        } else {
            MaterialTheme.colorScheme.surface
        }
    val edge =
        if (selected == true) MaterialTheme.colorScheme.primary else GlassDefaults.PanelHairline
    return this
        .clip(shape)
        .background(color = fill, shape = shape)
        .border(width = GlassDefaults.HairlineWidth, color = edge, shape = shape)
        .then(
            if (onLongClick == null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier.selectableCardClick(onClick = onClick, onLongClick = onLongClick)
            },
        ).padding(EpisodeCardPadding)
}

/** `EPISODE 1 · 62 MIN` — the number line the refresh puts above the episode's title. */
@Composable
private fun EpisodeNumberLine(episode: JellyfinItem) {
    val parts =
        buildList {
            episode.indexNumber?.let { add(stringResource(R.string.detail_episode_number, it)) }
            episode.runtimeMinutes?.let { add(stringResource(R.string.detail_runtime_minutes, it)) }
        }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(Separators.DOT).uppercase(),
        style = EpisodeNumberStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EpisodeTitle(episode: JellyfinItem) {
    Text(
        text = episode.name,
        style = EpisodeTitleStyle,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EpisodeOverview(episode: JellyfinItem) {
    val overview = episode.overview?.takeIf { it.isNotBlank() } ?: return
    Text(
        text = overview,
        style = EpisodeOverviewStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = OVERVIEW_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Play, or — while the list is in selection mode — this card's checkbox. */
@Composable
private fun EpisodeControl(
    onPlay: () -> Unit,
    selected: Boolean?,
    modifier: Modifier = Modifier,
) {
    if (selected == null) {
        GlassIconButton(
            icon = Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.detail_play_episode),
            onClick = onPlay,
            modifier = modifier,
        )
    } else {
        // `onCheckedChange = null` on purpose: the whole card is already the click target, and a
        // second one inside it would let a tap land on the box but not on the card it belongs to.
        Checkbox(checked = selected, onCheckedChange = null, modifier = modifier)
    }
}

/** Artwork width in the stacked card — the mocks' 120×68 thumbnail. */
private val STACKED_ART_WIDTH: Dp = 120.dp

/** Artwork width in a strip tile — the mocks' 150×84. */
private val STRIP_ART_WIDTH: Dp = 150.dp

/** Width of one strip tile on a wide layout. */
private val STRIP_CARD_WIDTH: Dp = 300.dp

private val EpisodeCardPadding = 12.dp

/** How much accent a selected card takes — enough to read down a list, not enough to hide text. */
private const val SELECTED_FILL_ALPHA = 0.14f

private const val OVERVIEW_LINES = 2

private val EpisodeNumberStyle =
    TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.08.em,
    )

private val EpisodeTitleStyle =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 18.sp,
    )

private val EpisodeOverviewStyle =
    TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

@Preview(name = "EpisodeRow", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun EpisodeRowPreview() {
    JellyfinTheme {
        EpisodeRow(episode = previewEpisode, onClick = {}, onPlay = {})
    }
}

@Preview(name = "EpisodeRow — selected", showBackground = true, backgroundColor = 0xFF101010, widthDp = 360)
@Composable
private fun EpisodeRowSelectedPreview() {
    JellyfinTheme {
        EpisodeRow(episode = previewEpisode, onClick = {}, onPlay = {}, onLongClick = {}, selected = true)
    }
}

@Preview(name = "EpisodeRow — strip", showBackground = true, backgroundColor = 0xFF101010, widthDp = 340)
@Composable
private fun EpisodeRowStripPreview() {
    JellyfinTheme {
        EpisodeRow(episode = previewEpisode, onClick = {}, onPlay = {}, strip = true)
    }
}

private val previewEpisode =
    JellyfinItem(
        id = "2",
        name = "The Bicameral Mind",
        type = ItemType.EPISODE,
        overview =
            "Dolores and Maeve approach full consciousness as the board moves against Ford, and the " +
                "season's threads converge on the maze.",
        indexNumber = 10,
        parentIndexNumber = 1,
        runTimeTicks = 54_000_000_000L,
    )
