package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.cardShadow
import java.util.Locale

/**
 * How every card in the design system reads its `width` parameter.
 *
 * A concrete [Dp] pins the card, which is what a horizontally scrolling row needs. [Dp.Unspecified]
 * fills whatever the parent offers, which is what a `GridCells.Adaptive` cell needs — and it does
 * so by measurement rather than by wrapping each cell in a `BoxWithConstraints`, i.e. without one
 * subcomposition per visible card while the grid scrolls.
 */
internal fun Modifier.cardWidth(width: Dp): Modifier = if (width.isSpecified) this.width(width) else this.fillMaxWidth()

/**
 * How every selectable row or card reacts to touch: tap does whatever the caller says, and a long
 * press — when the caller offers one — buzzes and enters batch-selection mode.
 *
 * The haptic is here rather than in the two screens because it is a property of the gesture, not of
 * the list: a long press that selects something with no tactile confirmation reads as a press that
 * did nothing until the eye finds the bar that appeared at the other end of the screen.
 *
 * The long press is *labelled* — "Select" — which is what puts it in TalkBack's actions menu. An
 * unlabelled long press is a gesture only a sighted user can discover, and batch selection is the
 * one mode of this app that has no other way in (accessibility audit 2026-08-05, A11Y-19).
 */
@Composable
fun Modifier.selectableCardClick(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    role: Role? = Role.Button,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val longClickLabel = stringResource(R.string.selection_enter)
    return this.combinedClickable(
        onClick = onClick,
        role = role,
        onLongClickLabel = onLongClick?.let { longClickLabel },
        onLongClick =
            onLongClick?.let { longClick ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                }
            },
    )
}

/** Title under a card's artwork. */
internal val CardTitleStyle =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 18.sp,
    )

/** Its second line — a year, a series name, an episode label. */
internal val CardSubtitleStyle =
    TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

/** Gap between the artwork and the title under it. */
internal val CardTitleGap = 10.dp

/** Gap between that title and its subtitle — tight, so the two read as one block. */
internal val CardSubtitleGap = 2.dp

/**
 * Font scale past which a card title is allowed a second line.
 *
 * 1.3 is where a 14sp title in a 130–232dp card stops fitting a useful number of characters on one:
 * below it the design's single line holds a real title, above it "The Bicameral…" becomes "The
 * Bic…" and the card stops distinguishing itself from its neighbour (audit SCALE-03).
 */
private const val TITLE_RELAX_SCALE = 1.3f

/** How many lines a card title gets at [fontScale] — one, or two once text is large. */
internal fun cardTitleMaxLines(fontScale: Float): Int = if (fontScale > TITLE_RELAX_SCALE) 2 else 1

/**
 * The title and subtitle every card draws under its artwork.
 *
 * Shared by [PosterCard] and [ThumbCard] because the two blocks were identical, and because both
 * halves of it — the scale-aware line count and the silence — are rules that must not drift apart:
 * the card's merged node speaks the untruncated title itself (see [mediaCardSemantics]), so these
 * two texts are pictures of words rather than words, and are cleared for the screen reader.
 */
@Composable
internal fun CardTitleBlock(
    title: String,
    subtitle: String?,
) {
    val maxLines = cardTitleMaxLines(LocalDensity.current.fontScale)
    Spacer(modifier = Modifier.height(CardTitleGap))
    Text(
        text = title,
        style = CardTitleStyle,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clearAndSetSemantics {},
    )
    if (subtitle != null) {
        Spacer(modifier = Modifier.height(CardSubtitleGap))
        Text(
            text = subtitle,
            style = CardSubtitleStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Text inside every overlay badge drawn on artwork — small, heavy and slightly tracked out. */
private val OverlayBadgeLabel =
    TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.06.em,
    )

/** Inset of the corner *circles* (watched, download, selection), tighter than [Dimens.OverlayInset]. */
private val CornerIndicatorInset = 8.dp

private val IndicatorSize = 22.dp

private val IndicatorGlyphSize = 13.dp

/** Ring thickness of the hollow "selectable but not selected" indicator. */
private val IndicatorRingWidth = 2.dp

private val BadgeRadius = Dimens.MPillRadius

private val BadgeVerticalPadding = 3.dp

private val TopBadgeHorizontalPadding = 8.dp

private val CornerBadgeHorizontalPadding = 7.dp

private val RatingStarSize = 10.dp

private val RatingStarGap = 3.dp

/** How far the time chip lifts when it would otherwise sit on the inset progress bar. */
private val TimeChipProgressOffset = 14.dp

private val SelectedOutlineWidth = 2.dp

/** Backdrops of the three overlay badges, from the mocks: top badge, time chip, rating badge. */
private val TopBadgeScrim = Color.Black.copy(alpha = 0.60f)

private val TimeChipScrim = Color.Black.copy(alpha = 0.70f)

private val RatingScrim = Color.Black.copy(alpha = 0.65f)

/** Backdrop of the hollow selection indicator, which has to stay visible over bright artwork. */
private val IndicatorScrim = Color.Black.copy(alpha = 0.60f)

private const val UNSELECTED_INDICATOR_ALPHA = 0.85f

/** Tint over selected artwork — enough to read as "picked", not so much that the image is gone. */
private const val SELECTED_TINT_ALPHA = 0.22f

/**
 * Track of the inset progress bar.
 *
 * 0.40, raised from 0.22 by the 2026-08-05 accessibility audit. This bar is the whole point of the
 * Continue Watching row — "how far in am I" is unreadable if the unfilled half is not there — so
 * under WCAG 1.4.11 it owes 3:1 against what it sits on. White@22% was 1.79:1 over the darkest
 * artwork it can land on; white@40% is 3.66:1 there, 3.82:1 on the `#101010` background.
 */
private const val PROGRESS_TRACK_ALPHA = 0.40f

/**
 * Formats a community rating for the corner badge: one decimal place, always.
 *
 * The trailing digit is not decoration. Ratings arrive as a float that is very often a whole number
 * (`8.0`), and rendering that as "8" beside a neighbouring card's "7.4" makes two values on the
 * same scale look like values on different ones. Locale-aware, because a decimal separator is not
 * universally a point.
 */
internal fun formatRatingBadge(
    rating: Float,
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, "%.1f", rating)

/**
 * Shared artwork block behind [PosterCard] and [ThumbCard]: the image itself plus the overlays
 * every card carries — the resume progress bar, the watched tick, the download badge, the optional
 * metadata badges, and (while a list is in batch-selection mode) the selection tint and indicator.
 *
 * @param selected `null` when the list is **not** in selection mode, which is the ordinary case and
 *   draws nothing extra; `false`/`true` put the card in the mode's unselected/selected state. One
 *   nullable flag rather than a pair of booleans because "selected while not selectable" is not a
 *   state that exists, and a pair would let a caller express it.
 * @param topStartBadge short, already-formatted label for the top-left glass badge — "S1 · E10",
 *   "4K". Suppressed in selection mode, where that corner belongs to the selection indicator.
 * @param timeChipText already-formatted remaining time for the bottom-right chip ("22m left"). The
 *   *number* comes from `JellyfinItem.remainingMinutes`; the wording is a caller's string resource,
 *   which is why this is a `String` and not an `Int`.
 * @param ratingBadge community rating for the bottom-left badge — see [formatRatingBadge].
 * @param contentDescription label for the artwork itself. Both cards pass `null`: the card they sit
 *   in is one merged semantics node carrying an authored description of the whole item (see
 *   [mediaCardSemantics]), and an image description would be concatenated onto it rather than
 *   replace it. Every overlay this draws is silenced for the same reason — the badges are drawn
 *   facts, and the card's sentence is where those facts are spoken.
 */
@Composable
internal fun MediaCardArtwork(
    imageUrl: String?,
    contentDescription: String?,
    aspectRatio: Float,
    downloadState: DownloadState,
    played: Boolean,
    progress: Float?,
    placeholderIcon: ImageVector?,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    topStartBadge: String? = null,
    timeChipText: String? = null,
    ratingBadge: Float? = null,
) {
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    JellyfinAsyncImage(
        url = imageUrl,
        contentDescription = contentDescription,
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .cardShadow(shape)
                .clip(shape),
        contentScale = ContentScale.Crop,
        placeholderIcon = placeholderIcon,
        overlay = {
            CardOverlays(
                shape = shape,
                downloadState = downloadState,
                played = played,
                progress = progress,
                selected = selected,
                topStartBadge = topStartBadge,
                timeChipText = timeChipText,
                ratingBadge = ratingBadge,
            )
        },
    )
}

@Composable
private fun BoxScope.CardOverlays(
    shape: RoundedCornerShape,
    downloadState: DownloadState,
    played: Boolean,
    progress: Float?,
    selected: Boolean?,
    topStartBadge: String?,
    timeChipText: String?,
    ratingBadge: Float?,
) {
    // Drawn over the image rather than under it: on a background this close to black, artwork with
    // dark edges has no visible boundary at all unless the hairline sits on top of it.
    Box(
        modifier =
            Modifier
                .matchParentSize()
                .border(GlassDefaults.HairlineWidth, GlassDefaults.ArtworkInnerHairline, shape),
    )

    if (selected == true) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_TINT_ALPHA))
                    .border(SelectedOutlineWidth, MaterialTheme.colorScheme.primary, shape),
        )
    }

    TopStartOverlay(selected = selected, topStartBadge = topStartBadge)

    // The download badge and the watched tick share the top-right corner, stacked rather than
    // overlaid: both are facts about the same item and neither replaces the other, and the common
    // case (one of the two, or neither) looks identical either way.
    Column(
        modifier = Modifier.align(Alignment.TopEnd).padding(CornerIndicatorInset),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        DownloadBadge(state = downloadState, decorative = true)
        // Hidden mid-episode (the progress bar already says "not finished"), and hidden in
        // selection mode, where a second primary circle with a check in it would be a puzzle.
        if (played && progress == null && selected == null) {
            WatchedIndicator()
        }
    }

    if (ratingBadge != null) {
        RatingBadge(
            rating = ratingBadge,
            modifier = Modifier.align(Alignment.BottomStart).padding(Dimens.OverlayInset),
        )
    }

    if (timeChipText != null) {
        TimeChip(
            text = timeChipText,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        start = Dimens.OverlayInset,
                        end = Dimens.OverlayInset,
                        bottom = if (progress != null) TimeChipProgressOffset else Dimens.OverlayInset,
                    ),
        )
    }

    if (progress != null) {
        InsetProgressBar(progress = progress, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The top-left corner, which one of two things can claim.
 *
 * In selection mode it is the indicator, always — a grid where only some cards said they were
 * selectable would read as a grid where only some cards can be picked. Otherwise it is the metadata
 * badge, when the caller passed one.
 */
@Composable
private fun BoxScope.TopStartOverlay(
    selected: Boolean?,
    topStartBadge: String?,
) {
    if (selected != null) {
        SelectionIndicator(
            selected = selected,
            modifier = Modifier.align(Alignment.TopStart).padding(CornerIndicatorInset),
            decorative = true,
        )
    } else if (topStartBadge != null) {
        Text(
            text = topStartBadge,
            style = OverlayBadgeLabel,
            color = Color.White,
            maxLines = 1,
            // Clipped inside artwork with no room to spare: without this the last glyph is cut in
            // half at ≥1.5× font scale rather than trailing off (audit SCALE-05).
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .clearAndSetSemantics {}
                    .padding(Dimens.OverlayInset)
                    .background(color = TopBadgeScrim, shape = RoundedCornerShape(BadgeRadius))
                    .padding(horizontal = TopBadgeHorizontalPadding, vertical = BadgeVerticalPadding),
        )
    }
}

/** The bottom-right "22m left" chip. */
@Composable
private fun TimeChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = OverlayBadgeLabel,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .clearAndSetSemantics {}
                .background(color = TimeChipScrim, shape = RoundedCornerShape(BadgeRadius))
                .padding(horizontal = CornerBadgeHorizontalPadding, vertical = BadgeVerticalPadding),
    )
}

/**
 * The bottom-left star + score badge, shown on library grids.
 *
 * Silent: the card's own description says "Rating 8.0 out of 10", which is the number *and* the
 * scale it is on — a bare "8.0" announced from a badge is a number out of nowhere (audit m1).
 */
@Composable
private fun RatingBadge(
    rating: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clearAndSetSemantics {}
                .background(color = RatingScrim, shape = RoundedCornerShape(BadgeRadius))
                .padding(horizontal = CornerBadgeHorizontalPadding, vertical = BadgeVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RatingStarGap),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(RatingStarSize),
        )
        Text(
            text = formatRatingBadge(rating),
            style = OverlayBadgeLabel,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The resume bar, inset from the artwork's edges rather than spanning them.
 *
 * A hand-rolled pair of boxes rather than a `LinearProgressIndicator`: at 3dp with a 2dp radius and
 * neither stop indicator nor gap, nothing that component provides survives being configured away.
 *
 * It carries no `progressBarRangeInfo` of its own (which is what the audit's A11Y-03 sketched):
 * inside a card the bar is not a control, it is one fact among six, and a separate progress node
 * would be a second stop announcing "45 percent" with nothing to say what is 45% done. The card's
 * merged description says "45% watched" instead, and the bar is explicitly silenced so the two
 * cannot both speak.
 */
@Composable
private fun InsetProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(Dimens.InsetProgressRadius)
    Box(
        modifier =
            modifier
                .clearAndSetSemantics {}
                .fillMaxWidth()
                .padding(horizontal = Dimens.OverlayInset)
                .padding(bottom = Dimens.SpaceSmall)
                .height(Dimens.InsetProgressHeight)
                .clip(trackShape)
                .background(Color.White.copy(alpha = PROGRESS_TRACK_ALPHA)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                    .height(Dimens.InsetProgressHeight)
                    .background(color = MaterialTheme.colorScheme.primary, shape = trackShape),
        )
    }
}

/**
 * The solid primary disc with a dark tick that marks an item as watched.
 *
 * Silent, like every overlay on a card: "Watched" is part of the card's own sentence, and a tick
 * that also announced itself would put the word in twice.
 */
@Composable
private fun WatchedIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(IndicatorSize)
                .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(IndicatorGlyphSize),
        )
    }
}

/**
 * The filled / hollow circle a card shows in selection mode.
 *
 * Both states are drawn, not just the selected one: an unselected card in a selection-mode grid has
 * to say that it *could* be selected, otherwise the mode looks like it applies to one card only.
 *
 * @param decorative `true` inside a card, whose own node now carries real `selected` semantics and
 *   a spoken state (see [mediaCardSemantics]) — a state a screen reader can *announce as a toggle*
 *   rather than a description that ends in the word "Selected". `false` leaves the indicator
 *   labelling itself, for a caller that draws one outside a selectable node.
 */
@Composable
internal fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    decorative: Boolean = false,
) {
    val description =
        stringResource(
            if (selected) R.string.selection_item_selected else R.string.selection_item_not_selected,
        ).takeUnless { decorative }
    if (selected) {
        Box(
            modifier =
                modifier
                    .size(IndicatorSize)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(IndicatorGlyphSize),
            )
        }
    } else {
        Box(
            modifier =
                modifier
                    .size(IndicatorSize)
                    .background(color = IndicatorScrim, shape = CircleShape)
                    .border(
                        width = IndicatorRingWidth,
                        color = Color.White.copy(alpha = UNSELECTED_INDICATOR_ALPHA),
                        shape = CircleShape,
                    ).semantics { description?.let { this.contentDescription = it } },
        )
    }
}
