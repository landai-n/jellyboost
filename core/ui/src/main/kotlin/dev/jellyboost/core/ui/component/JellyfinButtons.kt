package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.OverMedia
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.pageInk

// None of the three is an M3 `Button` on purpose: `Button` delegates to `Surface`, which applies
// `minimumInteractiveComponentSize()` *inside* the caller's chain, so a `.size(36.dp).glassSurface()`
// clipped and blurred the 48dp node below it and every glass circle drew at 48dp whatever diameter it
// declared. `Box`/`Row` restores the order: caller's modifier, 48dp frame, visual at its declared
// size, then the click target inside the visual's clip. The frame still reserves
// Dimens.MinTouchTarget on both axes, so a row of these is 48dp tall around a 36dp circle.

/**
 * The brand pill is the page's own ink, inverted — `onBackground` filled, `background` written on
 * it. In the dark scheme those *are* the white fill and `#101010` content the refresh drew
 * (DECISIONS 2026-08-01), so nothing moves there; a light page gets a near-black pill instead of a
 * white one on white. The player's play disc is the deliberate exception: it sits on video.
 */
private val PrimaryPillContainer: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground

private val PrimaryPillContent: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

private val PrimaryPillDisabledContainer: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.07f)

/**
 * A disabled label is still text and owes 4.5:1 — a busy pill is all an auth screen is saying. 0.35
 * measured 3.20:1 on `#101010`; 0.48 is 5.00:1 there and 4.78:1 on `#202020`. Dark ink is not
 * white's mirror: 0.48 of it is only 3.04:1 on `#EEF1F7`, so the light side runs at 0.65 — 5.08:1
 * on the page, 5.34:1 on a card. The faint *container* is what reads as un-pressable, not the label.
 */
private val PrimaryPillDisabledContent: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.48f, lightAlpha = 0.65f)

private val GhostPillContent: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground

private val GhostPillDisabledContent: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.48f, lightAlpha = 0.65f)

/** Disabled wins over [OverMedia]: no ghost pill in the app is both disabled and drawn on artwork. */
@Composable
@ReadOnlyComposable
private fun ghostPillContent(
    enabled: Boolean,
    overMedia: Boolean,
): Color =
    when {
        !enabled -> GhostPillDisabledContent
        overMedia -> OverMedia.GlassContent
        else -> GhostPillContent
    }

/** 5.65:1 over `ChromeFill` on a white frame in dark, 5.22:1 over the light one on a black frame. */
internal const val GLASS_ICON_TINT_ALPHA = 0.8f

val GlassIconTint: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = GLASS_ICON_TINT_ALPHA)

private val PillHorizontalPadding = 22.dp

private val PillHorizontalPaddingSmall = 16.dp

private val PillIconGap = 8.dp

private val PillIconSize = 18.dp

private val PillIconSizeSmall = 16.dp

/** Public with [GlassIconTint]: a glyph drawn outside this file (the cast button, whose tap is a
 * platform view) must share these or drift from the cluster it sits in. */
val GlassIconSize = 18.dp

private val PillSpinnerStroke = 2.dp

private val PillLabel =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W600,
    )

private val PillLabelSmall =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
    )

/**
 * @param overMedia the pill is drawn on artwork, so it keeps the dark scheme's inversion — a white
 *   fill with `#101010` ink — instead of the near-black pill a light *page* asks for.
 * @param loading only swaps the leading glyph for a spinner — the caller still disables the pill.
 */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
    leadingIcon: ImageVector? = null,
    overMedia: Boolean = false,
    loading: Boolean = false,
) {
    val container = if (overMedia) OverMedia.PillFill else PrimaryPillContainer
    val content = if (overMedia) OverMedia.PillInk else PrimaryPillContent
    PillFrame(
        onClick = onClick,
        enabled = enabled,
        height = if (small) Dimens.PillHeightSmall else Dimens.PillHeight,
        surface =
            Modifier.background(
                color = if (enabled) container else PrimaryPillDisabledContainer,
                shape = CircleShape,
            ),
        contentPadding = pillContentPadding(small),
        contentColor = if (enabled) content else PrimaryPillDisabledContent,
        stateDescription = busyStateDescription(loading),
        modifier = modifier,
    ) {
        PillContent(text = text, small = small, leadingIcon = leadingIcon, loading = loading)
    }
}

/**
 * @param overMedia moves the fill, the ink **and** the edge to [OverMedia] in one parameter: a pill
 *   inside a hero's copy lockup is drawn on something dark in both schemes. Passing the fill alone
 *   is the half-fix that leaves near-black text and a near-black edge on black glass, which is what
 *   this exists to make impossible — the three explicit parameters below stay for the player, whose
 *   fill is stronger still because there is no backdrop under it for the blur to pull down.
 * @param tint a pill floating over raw video (the player's skip offer) must pass a dark fill.
 * @param contentColor and [borderColor] travel with [tint], for [overMedia]'s reason.
 * @param loading only swaps the leading glyph for a spinner — the caller still disables the pill.
 */
@Composable
fun GhostPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
    leadingIcon: ImageVector? = null,
    overMedia: Boolean = false,
    tint: Color = if (overMedia) OverMedia.GlassFill else GlassDefaults.Fill,
    contentColor: Color = ghostPillContent(enabled = enabled, overMedia = overMedia),
    borderColor: Color = if (overMedia) OverMedia.GlassBorder else GlassDefaults.GhostBorder,
    loading: Boolean = false,
    progress: Float? = null,
    leadingIconTint: Color? = null,
) {
    PillFrame(
        onClick = onClick,
        enabled = enabled,
        height = if (small) Dimens.PillHeightSmall else Dimens.PillHeight,
        surface = Modifier.glassSurface(shape = CircleShape, borderColor = borderColor, tint = tint),
        contentPadding = pillContentPadding(small),
        contentColor = contentColor,
        stateDescription = busyStateDescription(loading),
        modifier = modifier,
    ) {
        PillContent(
            text = text,
            small = small,
            leadingIcon = leadingIcon,
            loading = loading,
            progress = progress,
            leadingIconTint = leadingIconTint,
        )
    }
}

/**
 * @param size diameter of the *drawn* circle only; the button always reserves
 *   [Dimens.MinTouchTarget] around it (see this file's header).
 * @param overMedia the circle floats over artwork the page has not scrimmed, so it takes
 *   [OverMedia.ChromeFill] and white — the in-lockup [OverMedia.GlassFill] is too weak there.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.PillHeightSmall,
    overMedia: Boolean = false,
    tint: Color = if (overMedia) OverMedia.GlassContent else GlassIconTint,
    surfaceTint: Color = if (overMedia) OverMedia.ChromeFill else GlassDefaults.Fill,
    borderColor: Color = if (overMedia) OverMedia.ChromeBorder else GlassDefaults.Hairline,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier.size(Dimens.MinTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // Click target inside the glass so the ripple clips to the visible circle, not the frame.
            // `mergeDescendants` keeps this button its own traversal stop: a merging node is not
            // swallowed by a merging ancestor, which is what saves Play inside a merged episode row.
            modifier =
                Modifier
                    .size(size)
                    .glassSurface(shape = CircleShape, borderColor = borderColor, tint = surfaceTint)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .semantics(mergeDescendants = true) {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = tint.alpha * DISABLED_GLYPH_FACTOR),
                modifier = Modifier.size(GlassIconSize),
            )
        }
    }
}

private const val DISABLED_GLYPH_FACTOR = 0.45f

/**
 * `propagateMinConstraints` passes a caller's `fillMaxWidth()`/`weight(1f)` minimum down to the drawn
 * pill; without it the pill hugs its label inside an empty frame.
 *
 * [height] is a floor, not a cap: `requiredHeight` would pin both ends and clip every pill's label at
 * font scales ≥1.5 (WCAG 1.4.4). It must stay `requiredHeightIn`, not `defaultMinSize` — the box's
 * 48dp minimum arrives non-zero, so `defaultMinSize` would do nothing and every pill would draw at
 * the touch frame's 48dp instead of its own 44/36dp.
 */
@Composable
private fun PillFrame(
    onClick: () -> Unit,
    enabled: Boolean,
    height: Dp,
    surface: Modifier,
    contentPadding: PaddingValues,
    contentColor: Color,
    stateDescription: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier.heightIn(min = Dimens.MinTouchTarget),
        propagateMinConstraints = true,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .requiredHeightIn(min = height)
                    .defaultMinSize(minWidth = Dimens.MinTouchTarget)
                    .clip(CircleShape)
                    .then(surface)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .semantics { stateDescription?.let { this.stateDescription = it } }
                    .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(PillIconGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

/** A disabled busy pill otherwise announces only "disabled"; "Busy" is a state, so the label survives. */
@Composable
private fun busyStateDescription(loading: Boolean): String? = stringResource(R.string.state_busy).takeIf { loading }

private fun pillContentPadding(small: Boolean): PaddingValues =
    PaddingValues(horizontal = if (small) PillHorizontalPaddingSmall else PillHorizontalPadding)

@Composable
private fun PillContent(
    text: String,
    small: Boolean,
    leadingIcon: ImageVector?,
    loading: Boolean = false,
    progress: Float? = null,
    leadingIconTint: Color? = null,
) {
    val iconSize = if (small) PillIconSizeSmall else PillIconSize
    when {
        loading ->
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                color = LocalContentColor.current,
                strokeWidth = PillSpinnerStroke,
            )

        progress != null ->
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(iconSize),
                color = leadingIconTint ?: LocalContentColor.current,
                strokeWidth = PillSpinnerStroke,
            )

        leadingIcon != null ->
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = leadingIconTint ?: LocalContentColor.current,
            )
    }
    Text(
        text = text,
        style = if (small) PillLabelSmall else PillLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Preview(name = "Pill buttons", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun PillButtonsPreview() {
    JellyfinTheme {
        Row(
            modifier = Modifier.padding(Dimens.SpaceLarge),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryPillButton(text = "Resume", onClick = {}, leadingIcon = Icons.Filled.PlayArrow)
            GhostPillButton(text = "Details", onClick = {}, leadingIcon = Icons.Outlined.Info)
            GlassIconButton(icon = Icons.Outlined.Search, contentDescription = "Search", onClick = {})
        }
    }
}

@Preview(name = "Pill buttons — small / disabled", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun PillButtonsSmallPreview() {
    JellyfinTheme {
        Box(modifier = Modifier.padding(Dimens.SpaceLarge)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
                PrimaryPillButton(text = "Retry", onClick = {}, small = true)
                PrimaryPillButton(text = "Connect", onClick = {}, small = true, enabled = false)
                GhostPillButton(text = "Download", onClick = {}, small = true)
            }
        }
    }
}
