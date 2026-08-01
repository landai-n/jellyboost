package dev.jellyboost.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/**
 * The home screen's immersive *Continue watching* banner: the first resume item's artwork running
 * full-bleed behind the status bar, with the copy and the two actions drawn on it (2026 refresh,
 * spec section 4a).
 *
 * The hero **is** the first card of the *Continue watching* row rather than an extra row above it —
 * `HomeRows` hands it `state.resume.first()` and draws the row from `drop(1)` — so the screen shows
 * the same items it always did, with the first one promoted instead of duplicated.
 *
 * ### Two shapes
 * [wide] picks between them, and `HomeRows` decides what "wide" means (see `isWideHome`). Compact is
 * the phone/portrait banner: copy in the bottom-left, the two pills sharing the width. Wide is the
 * landscape/tablet one: the copy block sits in the *left* third at [WideCopyTopPadding], over an
 * extra horizontal scrim, with room for the item's overview and content-width buttons.
 *
 * ### What this composable deliberately does not draw
 * The mock's search and avatar buttons in the top-right corner are the app frame's, not the hero's:
 * on a compact layout `AppActionCluster` floats its glass circles exactly there, over this banner
 * (see `AppScaffold`'s KDoc). The copy therefore always stays bottom-left or left, and nothing here
 * competes for that corner.
 *
 * @param height the banner's own height — not derived here, because `HomeRows` measures the viewport
 *   once for the whole screen and the same `BoxWithConstraints` decides [wide].
 * @param onResume the primary pill: play this item where it was left off.
 * @param onDetails the ghost pill: open the item's detail page, exactly as tapping its card does.
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
    Box(modifier = modifier.fillMaxWidth().height(height)) {
        HeroBackdrop(item = item, wide = wide)

        if (wide) {
            // The fade goes under the copy: a tall overview must never be the thing that fades out.
            HeroRailFade(modifier = Modifier.align(Alignment.BottomStart))
            WideHeroCopy(
                item = item,
                onResume = onResume,
                onDetails = onDetails,
                modifier = Modifier.align(Alignment.TopStart),
            )
        } else {
            CompactHeroCopy(
                item = item,
                onResume = onResume,
                onDetails = onDetails,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * The artwork and everything laid over it: the refresh's accent halo, the vertical scrim every
 * backdrop in the app carries, and — on a wide layout, where the copy sits beside the picture rather
 * than under it — a horizontal scrim that darkens the left third.
 *
 * `backdrop → thumb → primary` is `ThumbCard`'s fallback order, so the hero shows the same picture
 * the card it replaces would have shown when the server has no backdrop for the item.
 */
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
    Box(modifier = Modifier.fillMaxSize().background(JellyfinGradients.HeroHalo))
    Box(modifier = Modifier.fillMaxSize().background(JellyfinGradients.BackdropScrim))
    if (wide) {
        Box(modifier = Modifier.fillMaxSize().background(wideHeroScrim()))
    }
}

/** Bottom-left lockup: eyebrow, 34sp title, metadata, and two pills that split the width. */
@Composable
private fun CompactHeroCopy(
    item: JellyfinItem,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(CompactCopyPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        HeroEyebrow()
        HeroTitle(item = item, expanded = false)
        HeroMeta(item = item)
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
 * Left-hand lockup for a landscape or tablet window: the same block plus the overview, capped at
 * [WideCopyMaxWidth] so a paragraph never spans a 1200dp tablet, with buttons at their own width.
 *
 * [WideCopyTopPadding] clears the `GlassTopNav` that floats over this banner at these widths.
 */
@Composable
private fun WideHeroCopy(
    item: JellyfinItem,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(horizontal = Dimens.SpaceExtraLarge)
                .padding(top = WideCopyTopPadding)
                .widthIn(max = WideCopyMaxWidth),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        HeroEyebrow()
        HeroTitle(item = item, expanded = true)
        HeroMeta(item = item)
        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = OverviewStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = OVERVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
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

/** `● CONTINUE WATCHING` — the row's own title, reused as the banner's caption. */
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

/** The series a resume episode belongs to, or the item's own name — `JellyfinItem.displayTitle`. */
@Composable
private fun HeroTitle(
    item: JellyfinItem,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = item.displayTitle,
        style = if (expanded) JellyfinTypeExtras.HeroTitleExpanded else JellyfinTypeExtras.HeroTitleCompact,
        color = Color.White,
        maxLines = TITLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * `S1:E10 · TV-MA · 22 min left` — what is left of the item, in the shapes the refresh gives each
 * kind of fact: plain muted text, the outlined certificate badge, plain muted text again.
 *
 * A `FlowRow` because a long episode label plus a certificate plus the time left does not fit on one
 * line of a 360dp phone, and a clipped metadata line reads as a bug.
 */
@Composable
private fun HeroMeta(
    item: JellyfinItem,
    modifier: Modifier = Modifier,
) {
    val remaining = item.remainingMinutes
    if (item.episodeLabel == null && item.officialRating == null && remaining == null) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MetaGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        item.episodeLabel?.let { label ->
            HeroMetaText(text = label, modifier = Modifier.align(Alignment.CenterVertically))
        }
        item.officialRating?.let { certificate ->
            MPillBadge(text = certificate, modifier = Modifier.align(Alignment.CenterVertically))
        }
        remaining?.let { minutes ->
            HeroMetaText(
                text = pluralStringResource(R.plurals.home_minutes_left, minutes, minutes),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
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

/**
 * The wide banner's bottom fade.
 *
 * The rows below the hero come to rest [HeroRailOverlap] *inside* it (see `HomeRows`), which is the
 * mocks' `margin-top: -48px` rail. This gradient is what makes that land softly: by the height the
 * first row starts at, the artwork has already dissolved into the app background, so the row reads
 * as rising into the banner rather than as a card dropped on a picture.
 */
@Composable
private fun HeroRailFade(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val fade = remember(background) { Brush.verticalGradient(listOf(Color.Transparent, background)) }
    Box(modifier = modifier.fillMaxWidth().height(RailFadeHeight).background(fade))
}

/** *Resume* once there is a position to resume from, *Play* otherwise. */
@Composable
private fun resumeLabel(item: JellyfinItem): String =
    stringResource(if (item.userData.isResumable) R.string.home_hero_resume else R.string.home_hero_play)

/**
 * The mocks' landscape hero scrim: near-opaque background at the leading edge, gone by 70% across.
 *
 * Laid over `JellyfinGradients.BackdropScrim` rather than instead of it — the vertical scrim keeps
 * the banner's bottom edge blending into the page, this one buys the left-hand copy its contrast on
 * a picture that is now beside the text instead of behind it. Local to the home screen because it is
 * the only layout in the app that puts a title block *next* to a backdrop, and built here rather
 * than in `JellyfinGradients` because it is a function of the theme's background colour.
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

/** Gutter of the compact banner's copy — the mocks' 20dp hero padding. */
private val CompactCopyPadding = 20.dp

/** Where the wide copy block starts, clear of the 64dp glass top nav and the status bar above it. */
private val WideCopyTopPadding = 104.dp

/** Width cap of that block: about a third of a tablet, and never a full-width paragraph. */
private val WideCopyMaxWidth = 420.dp

/** How far the content rows overlap the wide banner — the mocks' negative rail margin. */
internal val HeroRailOverlap = 48.dp

/** Height of the fade that overlap lands in; deeper than the overlap so it is never a hard edge. */
private val RailFadeHeight = 96.dp

/** Diameter of the accent dot before the eyebrow, matching `MediaRow`'s section eyebrow. */
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
