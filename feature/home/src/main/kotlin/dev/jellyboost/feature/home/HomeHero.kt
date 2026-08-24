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
 * The home screen's immersive *Continue watching* banner: the first resume item's artwork running
 * full-bleed behind the status bar, with the copy and the two actions drawn on it.
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
    // The user's font scale is read once, here, and handed to whichever lockup is drawn: how much
    // copy fits in a fixed-height banner is a question about text, and every threshold below
    // answers it in dp (see "how much room a lockup needs" at the bottom of this file).
    val fontScale = LocalDensity.current.fontScale

    // Clipped so nothing the banner draws can ever land on the rows below it: the copy blocks are
    // height-bounded (each in its own way, see their KDocs), and this is the backstop that turns
    // that arithmetic into a guarantee at layouts the bounds were not computed for.
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

/**
 * The artwork and everything laid over it: the app's accent halo, the vertical scrim every
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
    Box(modifier = Modifier.fillMaxSize().heroHalo())
    Box(modifier = Modifier.fillMaxSize().background(JellyfinGradients.BackdropScrim))
    if (wide) {
        Box(modifier = Modifier.fillMaxSize().background(wideHeroScrim()))
    }
}

/**
 * Bottom-left lockup: eyebrow, 34sp title, metadata, and two pills that split the width.
 *
 * Anchored to the banner's *bottom* edge, so taller copy grows up over the artwork rather than
 * down into the rows — but "up" is only available while the banner is taller than the copy. On a
 * phone in landscape the 0.6-viewport cap squeezes the banner to ~216dp while the full lockup
 * wants ~230dp, and the overflow (buttons drawn through the metadata line, or past the banner's
 * bottom edge into the first content row) is the same failure mode the wide shape already guards
 * against. Below [compactHeroShowsSecondary]'s threshold the lockup therefore drops its two
 * secondary lines — the eyebrow and the metadata — and keeps what the banner is for: the title and
 * the actions.
 * Below [compactHeroTitleMaxLines]'s, the title gives up its second line too.
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
 * Left-hand lockup for a landscape or tablet window: the same block plus the overview, capped at
 * [WideCopyMaxWidth] so a paragraph never spans a 1200dp tablet, with buttons at their own width.
 *
 * ### Why this block is height-bounded
 * The banner is a *fixed-height* box and the rows below it come to rest [HeroRailOverlap] inside its
 * bottom edge, so copy that grows past that edge does not push anything — it draws straight over the
 * next section. A synopsis is exactly the block that can grow (an item with an overview is two to
 * three lines taller than one without), which is how the offline hero came to overlap the row under
 * it. So the column takes the whole banner, insets itself by [wideHeroCopyTopInset] above and the
 * rail below, and the overview alone is weighted: it is handed whatever is left once the eyebrow,
 * title, metadata and buttons have measured, and ellipsizes into it. The buttons therefore stay
 * inside the banner whatever the copy says, and `clipToBounds` makes that a guarantee rather than an
 * arithmetic argument.
 *
 * The compact lockup handles the same squeeze differently: it is anchored to the *bottom* edge, so
 * taller copy grows up over the artwork rather than down into the rows — and when the banner is too
 * short even for that, it sheds its secondary lines instead ([CompactHeroCopy]).
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
                // `fill = false`: the synopsis may take what is left, never demand it — a short
                // overview keeps its own height and the buttons stay under it instead of being
                // pushed to the bottom of the banner.
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

/**
 * The series a resume episode belongs to, or the item's own name — `JellyfinItem.displayTitle`.
 *
 * @param maxLines normally [TITLE_MAX_LINES]; one on a banner too short for two at the user's font
 *   scale (see [compactHeroTitleMaxLines]). The full title is spoken whatever is drawn.
 */
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
 * `S1:E10 · TV-MA · 22 min left` — what is left of the item, in the shape given to each kind of
 * fact: plain muted text, the outlined certificate badge, plain muted text again.
 *
 * A `FlowRow` because a long episode label plus a certificate plus the time left does not fit on one
 * line of a 360dp phone, and a clipped metadata line reads as a bug.
 *
 * To a screen reader it is **one** node, not three: read separately the line was "S1:E10", then
 * "TV-MA" — a bare certificate with nothing saying what it certifies — then "22 min left", three
 * stops before the buttons the banner exists for. Merged,
 * it is one sentence, and the certificate is qualified in words the way the badge's outline
 * qualifies it visually.
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
    // `describeParts` rather than a plain join: this line is one of three assemblers that need the
    // blank-trim — without it, a certificate the server returns as `""` would be announced as
    // "Rated , 22 minutes left".
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
    stringResource(if (item.userData.isResumable) R.string.home_hero_resume else CoreUiR.string.action_play)

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

// ---- how much room a lockup needs, and how that changes with the user's font scale -------------
//
// Every threshold below is calibrated in dp against a device at font scale 1.0. The banner is a
// fixed-height box while the copy inside it is `sp`; comparing the two directly, as if only one of
// them existed, goes silently wrong for anyone who has turned text up. At 1.5–2.0× the compact
// lockup would keep its eyebrow and metadata line while no longer fitting, and the wide one would
// draw its buttons straight through `clipToBounds`.
//
// Each lockup is therefore modelled as two numbers: the dp that never move (paddings, the gaps
// between blocks) and the part that is *text*, which is what `fontScale` stretches. The pill
// buttons count as text — their height is a floor, not a cap, so a label taller than the capsule
// grows it. Only the growth is applied, so every threshold is unchanged to the pixel at font
// scale 1.0.

/** How much taller than its calibrated size text is at [fontScale]; never negative. */
internal fun textGrowth(fontScale: Float): Float = (fontScale - 1f).coerceAtLeast(0f)

/**
 * The text in the compact lockup: a two-line 34sp title (38sp of line height each), the eyebrow,
 * the metadata line and the 48dp button frame.
 */
internal val CompactLockupText = 155.dp

/** The same, once the eyebrow and the metadata line have been shed — title plus buttons. */
private val CompactCondensedLockupText = 124.dp

/**
 * The text in the wide lockup: a two-line 44sp title (48sp of line height each), the eyebrow, the
 * metadata line and the button frame. The overview is not in here — it is the one weighted child,
 * and it gives up its room before anything else does ([WideHeroCopy]).
 */
internal val WideLockupText = 175.dp

/** The same, condensed to the title and the buttons. */
private val WideCondensedLockupText = 144.dp

/**
 * Whether a [heroHeight]-tall compact banner has room for the lockup's eyebrow and metadata lines.
 *
 * The full lockup's natural height is roughly 230dp — the two 20dp paddings, a two-line 34sp
 * title, the eyebrow, the metadata line, the 48dp button frame and three 12dp gaps — so a banner
 * under [CompactSecondaryMinHeight] cannot hold it: a phone in landscape (~360dp of viewport, so a
 * 216dp banner after [heroHeight]'s cap) is the everyday case. Without the two secondary lines the
 * lockup needs ~176dp and fits. The threshold carries a little slack over the 230dp so a banner
 * that would fit only at exactly font scale 1.0 does not thrash at the boundary.
 *
 * [fontScale] moves the threshold by exactly what the lockup's *text* grew by, so the calibrated
 * 260dp is what a default-scale device still sees, and a 2.0× device — whose lockup really is
 * ~155dp taller — sheds the two lines rather than drawing them over the title.
 *
 * A plain function of the height so the breakpoint is unit-testable without a device, like
 * [heroHeight] and [isWideHome].
 */
internal fun compactHeroShowsSecondary(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Boolean = heroHeight >= CompactSecondaryMinHeight + CompactLockupText * textGrowth(fontScale)

/** See [compactHeroShowsSecondary]. */
private val CompactSecondaryMinHeight = 260.dp

/**
 * Whether the wide banner's copy band has room for the same two secondary lines.
 *
 * The wide lockup is inset from the top and bounded below by the rail the rows overlap into, with
 * the overview as the only elastic child: once the overview has given up all of its room, the
 * eyebrow, title, metadata and buttons would overflow the band and `clipToBounds` would cut the
 * buttons off. Shedding the same two lines the compact shape sheds is what keeps the *actions*
 * inside the banner at large font scales.
 *
 * [WideSecondaryMinBand] is the full lockup at font scale 1.0: three 12dp gaps over the text in
 * [WideLockupText]. The mocks' 400dp banner has a 248dp band and the shortest banner the height cap
 * produces has 218dp, so at default scale nothing sheds — this is a large-font path only.
 */
internal fun wideHeroShowsSecondary(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Boolean = wideHeroCopyHeight(heroHeight) >= WideSecondaryMinBand + WideLockupText * textGrowth(fontScale)

/** See [wideHeroShowsSecondary]: the three inter-block gaps plus [WideLockupText]. */
private val WideSecondaryMinBand = 36.dp + WideLockupText

/**
 * How many lines the compact banner's title may take.
 *
 * The last resort, below shedding: when even the condensed lockup — title plus buttons, the two
 * things the banner exists for — cannot fit at this font scale, the title gives up its second line
 * rather than the buttons being clipped. A phone in landscape at 2.0× is the shape that gets here.
 *
 * [CompactCondensedMinHeight] is that lockup at font scale 1.0: two 20dp paddings, one 12dp gap and
 * [CompactCondensedLockupText].
 */
internal fun compactHeroTitleMaxLines(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Int =
    if (heroHeight >= CompactCondensedMinHeight + CompactCondensedLockupText * textGrowth(fontScale)) {
        TITLE_MAX_LINES
    } else {
        1
    }

/** See [compactHeroTitleMaxLines]. */
private val CompactCondensedMinHeight = 52.dp + CompactCondensedLockupText

/** [compactHeroTitleMaxLines] for the wide shape, measured against the copy band. */
internal fun wideHeroTitleMaxLines(
    heroHeight: Dp,
    fontScale: Float = 1f,
): Int =
    if (wideHeroCopyHeight(heroHeight) >= WideCondensedMinBand + WideCondensedLockupText * textGrowth(fontScale)) {
        TITLE_MAX_LINES
    } else {
        1
    }

/** See [wideHeroTitleMaxLines]: one 12dp gap over [WideCondensedLockupText]. */
private val WideCondensedMinBand = 12.dp + WideCondensedLockupText

/**
 * Where the wide copy block starts on the 400dp banner it was drawn for, clear of the 64dp glass top
 * nav and the status bar above it. [wideHeroCopyTopInset] is what the layout actually uses.
 */
private val WideCopyTopPadding = 104.dp

/**
 * How far down a [heroHeight]-tall wide banner the copy block starts.
 *
 * [WideCopyTopPadding] as a flat literal is a quarter of the mocks' 400dp banner, but `heroHeight`
 * caps the banner at three fifths of a short window: at 600dp of viewport the banner is 360dp and
 * the same 104dp would spend nearly a third of it on empty space above copy that then has nowhere
 * to end but over the rows below. Scaling it keeps the nav clear (the nav does not shrink, but at
 * 360dp of banner 94dp still clears its 64dp plus a status bar) and gives the block back the room
 * it needs. The fraction is calibrated so the mocks' banner is unchanged: 400 × 0.26 = 104.
 */
internal fun wideHeroCopyTopInset(heroHeight: Dp): Dp =
    (heroHeight * WIDE_COPY_TOP_FRACTION).coerceAtMost(WideCopyTopPadding)

/**
 * The vertical band the wide copy block has to itself: the banner minus the inset above it and the
 * rail the rows below overlap into ([HeroRailOverlap]).
 *
 * Not read by the layout — the column derives it from the same two insets — but it is the number
 * that decides whether the resume button clears the next section, so `HomeSizingTest` pins it.
 */
internal fun wideHeroCopyHeight(heroHeight: Dp): Dp =
    (heroHeight - wideHeroCopyTopInset(heroHeight) - HeroRailOverlap).coerceAtLeast(0.dp)

private const val WIDE_COPY_TOP_FRACTION = 0.26f

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
