package dev.jellyboost.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.component.MPillBadge
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.component.describeParts
import dev.jellyboost.core.ui.text.episodeNumberLabel
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.heroHalo
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The copy stays bottom-left (compact) or left (wide): `AppActionCluster` floats its glass circles
 * over this banner's top-right corner, so nothing here may compete for it.
 */
@Composable
internal fun HomeHero(
    item: JellyfinItem,
    wide: Boolean,
    height: Dp,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale

    // `clipToBounds` is the backstop: nothing the banner draws may land on the rows below it, at
    // layouts the copy blocks' height bounds were not computed for.
    Box(modifier = modifier.fillMaxWidth().height(height).clipToBounds()) {
        HeroBackdrop(item = item, wide = wide)

        if (wide) {
            // The fade goes under the copy: a tall overview must never be the thing that fades out.
            HeroRailFade(modifier = Modifier.align(Alignment.BottomStart))
            WideHeroCopy(
                item = item,
                heroHeight = height,
                fontScale = fontScale,
                onResume = onResume,
                onDetails = onDetails,
                modifier = Modifier.align(Alignment.TopStart),
            )
        } else {
            CompactHeroCopy(
                item = item,
                heroHeight = height,
                fontScale = fontScale,
                onResume = onResume,
                onDetails = onDetails,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/** `backdrop → thumb → primary` is `ThumbCard`'s fallback order — keep them in step. */
@Composable
private fun HeroBackdrop(
    item: JellyfinItem,
    wide: Boolean,
) {
    JellyfinAsyncImage(
        url = item.backdropImageUrl ?: item.thumbImageUrl ?: item.primaryImageUrl,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        placeholderIcon = Icons.Outlined.Movie,
    )
    Box(modifier = Modifier.fillMaxSize().heroHalo())
    Box(modifier = Modifier.fillMaxSize().background(JellyfinGradients.BackdropScrim))
    if (wide) {
        Box(modifier = Modifier.fillMaxSize().background(wideHeroScrim()))
    }
}

/**
 * Anchored to the banner's bottom edge so taller copy grows up over the artwork. When even that is
 * not enough it sheds the eyebrow and metadata ([compactHeroShowsSecondary]), then the title's
 * second line ([compactHeroTitleMaxLines]) — never the actions.
 */
@Composable
private fun CompactHeroCopy(
    item: JellyfinItem,
    heroHeight: Dp,
    fontScale: Float,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showSecondary = compactHeroShowsSecondary(heroHeight = heroHeight, fontScale = fontScale)

    Column(
        modifier = modifier.fillMaxWidth().padding(CompactCopyPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        if (showSecondary) HeroEyebrow()
        HeroTitle(
            item = item,
            expanded = false,
            maxLines = compactHeroTitleMaxLines(heroHeight = heroHeight, fontScale = fontScale),
        )
        if (showSecondary) HeroMeta(item = item)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
            PrimaryPillButton(
                text = resumeLabel(item),
                onClick = onResume,
                modifier = Modifier.weight(1f),
                leadingIcon = Icons.Filled.PlayArrow,
            )
            GhostPillButton(
                text = stringResource(R.string.home_hero_details),
                onClick = onDetails,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Height-bounded on purpose: the banner is fixed-height and copy that grows past its bottom edge
 * draws straight over the next section (an overview is two to three lines taller than none). The
 * overview is the one weighted child, so it absorbs the squeeze and the buttons stay inside.
 */
@Composable
private fun WideHeroCopy(
    item: JellyfinItem,
    heroHeight: Dp,
    fontScale: Float,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showSecondary = wideHeroShowsSecondary(heroHeight = heroHeight, fontScale = fontScale)

    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .clipToBounds()
                .padding(horizontal = Dimens.SpaceExtraLarge)
                .padding(top = wideHeroCopyTopInset(heroHeight), bottom = HeroRailOverlap)
                .widthIn(max = WideCopyMaxWidth),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        if (showSecondary) HeroEyebrow()
        HeroTitle(
            item = item,
            expanded = true,
            maxLines = wideHeroTitleMaxLines(heroHeight = heroHeight, fontScale = fontScale),
        )
        if (showSecondary) HeroMeta(item = item)
        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = OverviewStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = OVERVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                // `fill = false`: a short overview keeps its own height instead of pushing the
                // buttons to the bottom of the banner.
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
            PrimaryPillButton(
                text = resumeLabel(item),
                onClick = onResume,
                leadingIcon = Icons.Filled.PlayArrow,
            )
            GhostPillButton(text = stringResource(R.string.home_hero_details), onClick = onDetails)
        }
    }
}

@Composable
private fun HeroEyebrow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Box(
            modifier =
                Modifier
                    .size(EyebrowDotSize)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
        )
        Text(
            text = stringResource(R.string.home_section_continue_watching).uppercase(),
            style = JellyfinTypeExtras.Eyebrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The full title is spoken whatever [maxLines] draws. */
@Composable
private fun HeroTitle(
    item: JellyfinItem,
    expanded: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val title = item.displayTitle
    Text(
        text = title,
        style = if (expanded) JellyfinTypeExtras.HeroTitleExpanded else JellyfinTypeExtras.HeroTitleCompact,
        color = Color.White,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { contentDescription = title },
    )
}

/**
 * Merged into one spoken sentence: read separately, the bare certificate has nothing saying what it
 * certifies, and the reader takes three stops before the buttons the banner exists for.
 */
@Composable
private fun HeroMeta(
    item: JellyfinItem,
    modifier: Modifier = Modifier,
) {
    val remaining = item.remainingMinutes
    val episodeLabel = item.episodeNumberLabel()
    if (episodeLabel == null && item.officialRating == null && remaining == null) return

    val certificate = item.officialRating
    val ratedText = certificate?.let { stringResource(R.string.home_hero_rated, it) }
    val remainingText = remaining?.let { pluralStringResource(R.plurals.home_minutes_left, it, it) }
    // `describeParts`, not a join: the server can return a certificate as `""`, which a join would
    // announce as "Rated , 22 minutes left".
    val description = describeParts(episodeLabel, ratedText, remainingText)

    FlowRow(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(MetaGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        episodeLabel?.let { label ->
            HeroMetaText(text = label, modifier = Modifier.align(Alignment.CenterVertically))
        }
        certificate?.let {
            MPillBadge(text = it, modifier = Modifier.align(Alignment.CenterVertically))
        }
        remainingText?.let {
            HeroMetaText(text = it, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun HeroMetaText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MetaStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** What makes [HeroRailOverlap] safe: the artwork has dissolved into the background by then. */
@Composable
private fun HeroRailFade(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val fade = remember(background) { Brush.verticalGradient(listOf(Color.Transparent, background)) }
    Box(modifier = modifier.fillMaxWidth().height(RailFadeHeight).background(fade))
}

@Composable
private fun resumeLabel(item: JellyfinItem): String =
    stringResource(if (item.userData.isResumable) R.string.home_hero_resume else CoreUiR.string.action_play)

/**
 * Laid over `JellyfinGradients.BackdropScrim`, not instead of it; local to this file because it is a
 * function of the theme's background colour.
 */
@Composable
private fun wideHeroScrim(): Brush {
    val background = MaterialTheme.colorScheme.background
    return remember(background) {
        Brush.horizontalGradient(
            colorStops =
                arrayOf(
                    0f to background.copy(alpha = WIDE_SCRIM_NEAR_ALPHA),
                    WIDE_SCRIM_MID_STOP to background.copy(alpha = WIDE_SCRIM_MID_ALPHA),
                    WIDE_SCRIM_END_STOP to Color.Transparent,
                ),
        )
    }
}

private const val WIDE_SCRIM_NEAR_ALPHA = 0.94f

private const val WIDE_SCRIM_MID_STOP = 0.38f

private const val WIDE_SCRIM_MID_ALPHA = 0.72f

private const val WIDE_SCRIM_END_STOP = 0.70f

private val CompactCopyPadding = 20.dp

// Every threshold below is calibrated in dp at font scale 1.0. The banner is fixed-height dp while
// the copy is `sp`, so each lockup is modelled as fixed dp (paddings, gaps) plus the part that is
// *text* and stretches with `fontScale`. Pill buttons count as text: their height is a floor, not a
// cap. Only the growth is applied, so nothing moves at scale 1.0.

internal fun textGrowth(fontScale: Float): Float = (fontScale - 1f).coerceAtLeast(0f)

/** Two-line 34sp title, eyebrow, metadata line, 48dp button frame. */
internal val CompactLockupText = 155.dp

/** The same once the eyebrow and metadata line are shed. */
private val CompactCondensedLockupText = 124.dp

/** Two-line 44sp title, eyebrow, metadata line, button frame; the weighted overview is excluded. */
internal val WideLockupText = 175.dp

private val WideCondensedLockupText = 144.dp

/**
 * The full lockup is ~230dp against a landscape phone's ~216dp banner; without the two secondary
 * lines it needs ~176dp. The threshold carries slack over 230dp so the boundary does not thrash.
 */
internal fun compactHeroShowsSecondary(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Boolean = heroHeight >= CompactSecondaryMinHeight + CompactLockupText * textGrowth(fontScale)

private val CompactSecondaryMinHeight = 260.dp

/**
 * A large-font path only: nothing sheds at default scale. Once the overview has given up all its
 * room, shedding these two lines is what keeps the buttons from being cut by `clipToBounds`.
 */
internal fun wideHeroShowsSecondary(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Boolean = wideHeroCopyHeight(heroHeight) >= WideSecondaryMinBand + WideLockupText * textGrowth(fontScale)

/** The three inter-block gaps plus [WideLockupText]. */
private val WideSecondaryMinBand = 36.dp + WideLockupText

/** The last resort below shedding: the title loses its second line rather than the buttons clipping. */
internal fun compactHeroTitleMaxLines(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Int =
    if (heroHeight >= CompactCondensedMinHeight + CompactCondensedLockupText * textGrowth(fontScale)) {
        TITLE_MAX_LINES
    } else {
        1
    }

/** Two 20dp paddings and one 12dp gap over [CompactCondensedLockupText]. */
private val CompactCondensedMinHeight = 52.dp + CompactCondensedLockupText

internal fun wideHeroTitleMaxLines(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Int =
    if (wideHeroCopyHeight(heroHeight) >= WideCondensedMinBand + WideCondensedLockupText * textGrowth(fontScale)) {
        TITLE_MAX_LINES
    } else {
        1
    }

/** One 12dp gap over [WideCondensedLockupText]. */
private val WideCondensedMinBand = 12.dp + WideCondensedLockupText

/** Ceiling only; clears the 64dp glass top nav and the status bar on the 400dp banner. */
private val WideCopyTopPadding = 104.dp

/**
 * Scaled rather than flat: on a short window the banner is capped at 360dp, where a fixed 104dp
 * would spend a third of it on empty space and push the copy over the rows. 400 × 0.26 = 104, so
 * the full-height banner is unchanged, and 94dp still clears the nav at 360dp.
 */
internal fun wideHeroCopyTopInset(heroHeight: Dp): Dp =
    (heroHeight * WIDE_COPY_TOP_FRACTION).coerceAtMost(WideCopyTopPadding)

/**
 * Not read by the layout, which derives it from the same two insets — but it decides whether the
 * resume button clears the next section, so `HomeSizingTest` pins it.
 */
internal fun wideHeroCopyHeight(heroHeight: Dp): Dp =
    (heroHeight - wideHeroCopyTopInset(heroHeight) - HeroRailOverlap).coerceAtLeast(0.dp)

private const val WIDE_COPY_TOP_FRACTION = 0.26f

private val WideCopyMaxWidth = 420.dp

/** How far the content rows overlap the wide banner. */
internal val HeroRailOverlap = 48.dp

/** Deeper than [HeroRailOverlap], so the overlap never lands on a hard edge. */
private val RailFadeHeight = 96.dp

private val EyebrowDotSize = 6.dp

private val MetaGap = 10.dp

private const val TITLE_MAX_LINES = 2

private const val OVERVIEW_MAX_LINES = 3

private val MetaStyle =
    TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

private val OverviewStyle =
    TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.W400,
    )
