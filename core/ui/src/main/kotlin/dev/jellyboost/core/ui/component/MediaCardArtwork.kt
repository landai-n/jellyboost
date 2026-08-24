package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.text.subtitleLine
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.POSTER_ASPECT_RATIO
import dev.jellyboost.core.ui.theme.THUMB_ASPECT_RATIO
import dev.jellyboost.core.ui.theme.cardShadow
import java.util.Locale

/**
 * [Dp.Unspecified] fills the parent (adaptive grid cell); a concrete [Dp] pins it (scrolling row).
 * Done by measurement, not `BoxWithConstraints`, to avoid a subcomposition per visible card.
 */
internal fun Modifier.cardWidth(width: Dp): Modifier = if (width.isSpecified) this.width(width) else this.fillMaxWidth()

/**
 * The long press must stay labelled: the label is what puts batch selection in TalkBack's actions
 * menu, and there is no other way into that mode.
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

internal val CardTitleStyle =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 18.sp,
    )

internal val CardSubtitleStyle =
    TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

internal val CardTitleGap = 10.dp

internal val CardSubtitleGap = 2.dp

/** Above 1.3, a 14sp title in a 130–232dp card no longer fits a distinguishing number of glyphs. */
private const val TITLE_RELAX_SCALE = 1.3f

internal fun cardTitleMaxLines(fontScale: Float): Int = if (fontScale > TITLE_RELAX_SCALE) 2 else 1

/**
 * Both texts stay semantics-cleared: the card's merged node already speaks the untruncated title
 * (see [mediaCardSemantics]), so leaving them audible says it twice, truncated.
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

private val OverlayBadgeLabel =
    TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.06.em,
    )

private val CornerIndicatorInset = 8.dp

private val IndicatorSize = 22.dp

private val IndicatorGlyphSize = 13.dp

private val IndicatorRingWidth = 2.dp

private val BadgeRadius = Dimens.MPillRadius

private val BadgeVerticalPadding = 3.dp

private val TopBadgeHorizontalPadding = 8.dp

private val CornerBadgeHorizontalPadding = 7.dp

private val RatingStarSize = 10.dp

private val RatingStarGap = 3.dp

private val TimeChipProgressOffset = 14.dp

private val SelectedOutlineWidth = 2.dp

private val TopBadgeScrim = Color.Black.copy(alpha = 0.60f)

private val TimeChipScrim = Color.Black.copy(alpha = 0.70f)

private val RatingScrim = Color.Black.copy(alpha = 0.65f)

private val IndicatorScrim = Color.Black.copy(alpha = 0.60f)

private const val UNSELECTED_INDICATOR_ALPHA = 0.85f

private const val SELECTED_TINT_ALPHA = 0.22f

/**
 * WCAG 1.4.11 wants 3:1 for this track. White@22% measured 1.79:1 over the darkest artwork it can
 * land on; white@40% is 3.66:1 there and 3.82:1 on the `#101010` background. Do not lower it.
 */
private const val PROGRESS_TRACK_ALPHA = 0.40f

/**
 * Always one decimal — "8" beside a neighbouring "7.4" reads as a different scale — and always
 * locale-aware. Public so no caller re-rolls it with a hardcoded `Locale.US`; a second copy once
 * put `8.6` beside `8,6` on the same screen.
 */
fun formatRatingBadge(
    rating: Float,
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, "%.1f", rating)

internal sealed interface CardShape {
    val aspectRatio: Float

    val placeholderIcon: ImageVector

    fun imageUrl(item: JellyfinItem): String?

    data object Poster : CardShape {
        override val aspectRatio = POSTER_ASPECT_RATIO
        override val placeholderIcon = Icons.Outlined.Movie

        override fun imageUrl(item: JellyfinItem) = item.primaryImageUrl
    }

    data object Thumb : CardShape {
        override val aspectRatio = THUMB_ASPECT_RATIO
        override val placeholderIcon = Icons.Outlined.Tv

        override fun imageUrl(item: JellyfinItem) = item.thumbImageUrl ?: item.backdropImageUrl ?: item.primaryImageUrl
    }
}

/**
 * [mediaCardSemantics] must stay *before* the click in the modifier chain: that ordering is what
 * makes the card one traversal stop with an authored sentence rather than six.
 *
 * A null [onClick] means the card sits inside something already clickable (`EpisodeRow`), so it is
 * cleared entirely rather than becoming a second stop offering the row's own action.
 */
@Composable
internal fun MediaCard(
    shape: CardShape,
    item: JellyfinItem,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    width: Dp,
    showTitle: Boolean,
    onLongClick: (() -> Unit)?,
    overlays: CardOverlayFacts,
) {
    val description =
        mediaCardDescription(
            item = item,
            badge = overlays.topStartBadge,
            timeChipText = overlays.timeChipText,
            ratingBadge = overlays.ratingBadge,
        )
    Column(
        modifier =
            modifier
                .cardWidth(width)
                .then(
                    when {
                        onClick == null -> Modifier.clearAndSetSemantics {}
                        onLongClick == null ->
                            mediaCardSemantics(description = description, selected = overlays.selected)
                                .clickable(role = Role.Button, onClick = onClick)

                        else ->
                            mediaCardSemantics(description = description, selected = overlays.selected)
                                .selectableCardClick(onClick = onClick, onLongClick = onLongClick)
                    },
                ),
    ) {
        MediaCardArtwork(
            imageUrl = shape.imageUrl(item),
            contentDescription = null,
            aspectRatio = shape.aspectRatio,
            downloadState = item.downloadState,
            played = item.userData.played,
            progress = item.playbackProgress,
            placeholderIcon = shape.placeholderIcon,
            overlays = overlays,
        )

        if (showTitle) {
            CardTitleBlock(title = item.displayTitle, subtitle = item.subtitleLine())
        }
    }
}

/**
 * All four end up in the card's spoken sentence via `mediaCardDescription`.
 *
 * @param selected `null` means the list is not in selection mode at all; `false`/`true` are the
 *   mode's two states. Not a pair of booleans, which could express "selected while not selectable".
 */
@Immutable
internal data class CardOverlayFacts(
    val selected: Boolean? = null,
    val topStartBadge: String? = null,
    val timeChipText: String? = null,
    val ratingBadge: Float? = null,
)

/**
 * Every overlay drawn here is semantics-cleared, and both cards pass a `null` [contentDescription]:
 * the card's merged node (see [mediaCardSemantics]) already speaks these facts, and anything
 * audible here is concatenated onto that sentence rather than replacing it.
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
    overlays: CardOverlayFacts = CardOverlayFacts(),
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
                overlays = overlays,
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
    overlays: CardOverlayFacts,
) {
    val selected = overlays.selected
    // Over the image, not under it: dark-edged artwork has no visible boundary on a near-black
    // background unless the hairline sits on top.
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

    TopStartOverlay(selected = selected, topStartBadge = overlays.topStartBadge)

    Column(
        modifier = Modifier.align(Alignment.TopEnd).padding(CornerIndicatorInset),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        DownloadBadge(state = downloadState, decorative = true)
        // Hidden mid-episode (the progress bar says it) and in selection mode (a second primary
        // circle with a check in it reads as the selection indicator).
        if (played && progress == null && selected == null) {
            WatchedIndicator()
        }
    }

    val ratingBadge = overlays.ratingBadge
    if (ratingBadge != null) {
        RatingBadge(
            rating = ratingBadge,
            modifier = Modifier.align(Alignment.BottomStart).padding(Dimens.OverlayInset),
        )
    }

    val timeChipText = overlays.timeChipText
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

/** In selection mode the indicator claims this corner unconditionally, badge or no badge. */
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
 * Boxes rather than `LinearProgressIndicator`: at 3dp with a 2dp radius and neither stop indicator
 * nor gap, nothing that component provides survives being configured away.
 *
 * Deliberately carries no `progressBarRangeInfo` — a separate progress node would announce "45
 * percent" with nothing to say what is 45% done; the card's merged description says "45% watched".
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
 * @param decorative `true` inside a card, whose own node already carries `selected` semantics (see
 *   [mediaCardSemantics]); `false` leaves the indicator labelling itself, for a caller that draws
 *   one outside a selectable node.
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
