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
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.OverMedia
import dev.jellyboost.core.ui.theme.backdropScrim
import dev.jellyboost.core.ui.theme.heroHalo
import dev.jellyboost.core.ui.theme.wideHeroWash
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The copy stays bottom-left (compact) or left (wide): `AppActionCluster` floats its glass circles
 * over this banner's top-right corner, so nothing here may compete for it.
 *
 * Everything the banner draws is `OverMedia`'s, in both schemes: the artwork is dark-scrimmed either
 * way, so a light page below it changes where the banner *ends*, never what it is written in.
 */
@Composable
internal fun HomeHero(
    item: JellyfinItem,
    wide: Boolean,
    portrait: Boolean,
    height: Dp,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale

    // `clipToBounds` is the backstop: nothing the banner draws may land on the rows below it, at
    // layouts the copy blocks' height bounds were not computed for.
    Box(modifier = modifier.fillMaxWidth().height(height).clipToBounds()) {
        HeroBackdrop(
            item = item,
            wide = wide,
            copyZone = if (wide) 0.dp else compactHeroCopyZone(heroHeight = height, fontScale = fontScale),
        )

        if (wide) {
            // The fade goes under the copy: a tall overview must never be the thing that fades out.
            if (OverMedia.artworkDissolvesIntoPage) {
                HeroRailFade(modifier = Modifier.align(Alignment.BottomStart))
            }
            WideHeroCopy(
                item = item,
                heroHeight = height,
                fontScale = fontScale,
                portrait = portrait,
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

/**
 * `backdrop → thumb → primary` is `ThumbCard`'s fallback order — keep them in step.
 *
 * @param copyZone zero on the wide layout: its copy is top-inset and left-anchored, so the wash and
 *   not the vertical ramp is the ground under it.
 */
@Composable
private fun HeroBackdrop(
    item: JellyfinItem,
    wide: Boolean,
    copyZone: Dp,
) {
    JellyfinAsyncImage(
        url = item.backdropImageUrl ?: item.thumbImageUrl ?: item.primaryImageUrl,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        placeholderIcon = Icons.Outlined.Movie,
    )
    Box(modifier = Modifier.fillMaxSize().heroHalo())
    Box(modifier = Modifier.fillMaxSize().backdropScrim(copyZone = copyZone))
    if (wide) {
        Box(modifier = Modifier.fillMaxSize().wideHeroWash(copyEdge = WideCopyEdge))
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
                overMedia = true,
            )
            GhostPillButton(
                text = stringResource(R.string.home_hero_details),
                onClick = onDetails,
                modifier = Modifier.weight(1f),
                overMedia = true,
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
    portrait: Boolean,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = wideHeroCopyBottomInset(OverMedia.artworkDissolvesIntoPage)
    val showSecondary =
        wideHeroShowsSecondary(heroHeight = heroHeight, fontScale = fontScale, bottomInset = bottomInset)

    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .clipToBounds()
                .padding(horizontal = Dimens.SpaceExtraLarge)
                .padding(top = wideHeroCopyTopInset(heroHeight), bottom = bottomInset)
                .widthIn(max = WideCopyMaxWidth),
        // Anchoring follows the window's orientation, the canvas's split: the portrait mocks seat
        // the lockup against the banner's bottom edge with the artwork breathing above it, the
        // landscape mock hangs it from the nav inset with the overview filling downward. A portrait
        // banner top-anchored strands short copy over a dead band — the mismatch that prompted this.
        verticalArrangement =
            Arrangement.spacedBy(
                Dimens.SpaceMedium,
                if (portrait) Alignment.Bottom else Alignment.Top,
            ),
    ) {
        if (showSecondary) HeroEyebrow()
        HeroTitle(
            item = item,
            expanded = true,
            maxLines = wideHeroTitleMaxLines(heroHeight = heroHeight, fontScale = fontScale, bottomInset = bottomInset),
        )
        if (showSecondary) HeroMeta(item = item)
        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = OverviewStyle,
                color = OverMedia.Meta,
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
                overMedia = true,
            )
            GhostPillButton(
                text = stringResource(R.string.home_hero_details),
                onClick = onDetails,
                overMedia = true,
            )
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
                    .background(color = OverMedia.Accent, shape = CircleShape),
        )
        Text(
            text = stringResource(R.string.home_section_continue_watching).uppercase(),
            style = JellyfinTypeExtras.Eyebrow,
            color = OverMedia.Eyebrow,
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
        color = OverMedia.Title,
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
            MPillBadge(
                text = it,
                modifier = Modifier.align(Alignment.CenterVertically),
                overMedia = true,
            )
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
        color = OverMedia.Meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * What makes [HeroRailOverlap] safe: the artwork has dissolved into the background by then. Drawn
 * only where it has — see [OverMedia.artworkDissolvesIntoPage].
 */
@Composable
private fun HeroRailFade(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val fade = remember(background) { Brush.verticalGradient(listOf(Color.Transparent, background)) }
    Box(modifier = modifier.fillMaxWidth().height(RailFadeHeight).background(fade))
}

@Composable
private fun resumeLabel(item: JellyfinItem): String =
    stringResource(if (item.userData.isResumable) R.string.home_hero_resume else CoreUiR.string.action_play)

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
 * Everything the lockup occupies above the banner's foot — its text at the current scale plus the
 * fixed frame around it. It is what the scrim's plateau is anchored to (`Modifier.backdropScrim`),
 * so it is the honest ceiling of the copy zone rather than a fraction of the banner: at scale 1.0 on
 * a 460dp banner it is 231dp (the copy starts halfway down), and at 2.0 it is 386dp of a 615dp one.
 *
 * Deliberately the *two-line* title's height even where [compactHeroTitleMaxLines] has dropped to
 * one: over-measuring the copy zone only starts the plateau higher, which is the safe direction.
 */
internal fun compactHeroCopyZone(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Dp {
    val scale = 1f + textGrowth(fontScale)
    return if (compactHeroShowsSecondary(heroHeight = heroHeight, fontScale = fontScale)) {
        CompactLockupFrame + CompactLockupText * scale
    } else {
        CompactCondensedLockupFrame + CompactCondensedLockupText * scale
    }
}

/** Two 20dp paddings and the three gaps between four blocks. */
private val CompactLockupFrame = CompactCopyPadding * 2 + Dimens.SpaceMedium * 3

/** The same once the eyebrow and metadata line are shed: two blocks, one gap. */
private val CompactCondensedLockupFrame = CompactCopyPadding * 2 + Dimens.SpaceMedium

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
    bottomInset: Dp = HeroRailOverlap,
): Boolean =
    wideHeroCopyHeight(heroHeight, bottomInset) >= WideSecondaryMinBand + WideLockupText * textGrowth(fontScale)

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
    bottomInset: Dp = HeroRailOverlap,
): Int =
    if (wideHeroCopyHeight(heroHeight, bottomInset) >=
        WideCondensedMinBand + WideCondensedLockupText * textGrowth(fontScale)
    ) {
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
internal fun wideHeroCopyHeight(
    heroHeight: Dp,
    bottomInset: Dp = HeroRailOverlap,
): Dp = (heroHeight - wideHeroCopyTopInset(heroHeight) - bottomInset).coerceAtLeast(0.dp)

/**
 * The rail's overlap where the rows climb into the banner; a plain margin where the light ramp ends
 * the banner on a hard edge and nothing climbs in — holding the overlap there squeezes the copy by
 * 48dp and leaves the same 48dp as a dead scrimmed band under the buttons.
 */
internal fun wideHeroCopyBottomInset(artworkDissolvesIntoPage: Boolean): Dp =
    if (artworkDissolvesIntoPage) HeroRailOverlap else Dimens.SpaceExtraLarge

private const val WIDE_COPY_TOP_FRACTION = 0.26f

private val WideCopyMaxWidth = 420.dp

/**
 * The copy column's far edge, and therefore how far the wash has to hold its plateau. In **dp**,
 * because the column is: the padding is fixed and [WideCopyMaxWidth] is a cap the column reaches on
 * every window the wide layout runs at (the narrowest is 600dp, which leaves it 552dp to fill).
 */
internal val WideCopyEdge = Dimens.SpaceExtraLarge + WideCopyMaxWidth

/**
 * How far the content rows overlap the wide banner — where the artwork dissolves into the page
 * ([OverMedia.artworkDissolvesIntoPage]); under the light ramp the rows start at the banner's edge
 * instead, and the copy's bottom inset shrinks with them ([wideHeroCopyBottomInset]).
 */
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
