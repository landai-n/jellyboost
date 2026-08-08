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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.jellyboost.core.ui.theme.glassSurface

// The two pill buttons and the glass icon button the 2026 refresh uses for every action in the app
// (DECISIONS.md 2026-08-01, "primary action buttons are white").
//
// The primary pill is white with `#101010` content rather than `colorScheme.primary`: the palette's
// `#00A4DC` stays the *accent* — progress, selection, links — and a white fill is what makes the one
// action a screen wants the user to take unmistakable against the near-black background. That is a
// deliberate departure from stock Material, which is why these are wrappers over `ButtonDefaults`
// rather than a re-tuned colour scheme: every call site names the component it wants, and the choice
// stays greppable and reversible.
//
// ### Why none of the three is an M3 `Button`
// They were, until the drawn surfaces turned out not to be the size they asked for. `Button`
// delegates to `Surface`, which applies `Modifier.minimumInteractiveComponentSize()` *inside* the
// caller's modifier chain:
//
//     modifier /* the caller's */ → minimumInteractiveComponentSize() → surface() → clickable()
//
// The min-size node reports 48dp whatever it wraps, so a caller's `.size(36.dp).glassSurface(…)`
// clipped, blurred and outlined the node *below* it — 48dp — and every glass circle in the app came
// out the same size regardless of the diameter it declared (36, 44 and 34dp call sites all drew at
// 48). Building the three from `Box`/`Row` puts the order back the right way round: the caller's
// modifier stays outermost (so `fillMaxWidth`/`weight` still work), then the 48dp frame, then the
// visual at its declared size, then the click target and its ripple *inside* the visual's clip.
//
// Nothing is given up on accessibility: the frame reserves [Dimens.MinTouchTarget] on both axes, so
// the automatic 48dp touch slop Compose gives every pointer-input node has room to live in and
// neighbouring controls cannot eat into it. What changes is that a row of these buttons is now
// 48dp tall with a 36dp circle drawn in the middle of it, rather than a 48dp circle — which is why
// call sites that space them apart may want *less* arrangement spacing than before.

/** Container of a primary pill. Not `colorScheme.primary` — see the file KDoc. */
private val PrimaryPillContainer = Color.White

/** Content on that white fill: the app background colour, for maximum contrast. */
private val PrimaryPillContent = Color(0xFF101010)

/** Disabled primary fill — present enough to hold the button's shape, too faint to invite a tap. */
private val PrimaryPillDisabledContainer = Color.White.copy(alpha = 0.07f)

/**
 * Disabled content, on both pills.
 *
 * A disabled control still has to say *what* it is — a busy primary pill is the only thing on an
 * auth screen telling the user what is happening — so its label is text with a 4.5:1 obligation,
 * not decoration. At 0.35 it was 3.20:1 on `#101010`; 0.48 is 5.00:1 there and 4.78:1 on `#202020`
 * (accessibility audit 2026-08-05). Still visibly a disabled label: [PrimaryPillDisabledContainer]
 * is what says "you cannot press this", and it is untouched.
 */
private val PrimaryPillDisabledContent = Color.White.copy(alpha = 0.48f)

/** Content colour of a ghost pill and its disabled counterpart. */
private val GhostPillContent = Color.White

private val GhostPillDisabledContent = Color.White.copy(alpha = 0.48f)

/** Default tint of a [GlassIconButton]'s glyph — white, held just off full strength. */
val GlassIconTint: Color = Color.White.copy(alpha = 0.8f)

/** Horizontal padding inside a full-size pill, and inside a small one. */
private val PillHorizontalPadding = 22.dp

private val PillHorizontalPaddingSmall = 16.dp

/** Gap between a pill's leading icon and its label. */
private val PillIconGap = 8.dp

private val PillIconSize = 18.dp

private val PillIconSizeSmall = 16.dp

/** Glyph size inside a [GlassIconButton], independent of the button's own diameter. */
private val GlassIconSize = 18.dp

/** Track width of the inline busy spinner a `loading` pill draws in place of its leading icon. */
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
 * The white pill that carries a screen's primary action: Play, Resume, Sign in, Connect, Retry.
 *
 * @param small the 36dp variant, for actions that sit inside dense chrome (a state view's retry, a
 *   row of bulk actions) rather than at the head of a screen.
 * @param leadingIcon drawn before the label at 18dp (16dp when [small]); `null` leaves a label-only
 *   pill, which is the common case.
 * @param loading replaces the leading icon with an inline spinner in the disabled content color —
 *   for the moment between tapping the action and its result (signing in, connecting). The caller
 *   still disables the button; [loading] only adds the busy glyph.
 */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
    leadingIcon: ImageVector? = null,
    loading: Boolean = false,
) {
    PillFrame(
        onClick = onClick,
        enabled = enabled,
        height = if (small) Dimens.PillHeightSmall else Dimens.PillHeight,
        surface =
            Modifier.background(
                color = if (enabled) PrimaryPillContainer else PrimaryPillDisabledContainer,
                shape = CircleShape,
            ),
        contentPadding = pillContentPadding(small),
        contentColor = if (enabled) PrimaryPillContent else PrimaryPillDisabledContent,
        stateDescription = busyStateDescription(loading),
        modifier = modifier,
    ) {
        PillContent(text = text, small = small, leadingIcon = leadingIcon, loading = loading)
    }
}

/**
 * The glass pill that carries a screen's *secondary* action, beside a [PrimaryPillButton].
 *
 * Same geometry as the primary so the two sit level in a row; a glass fill and a white@12% edge
 * instead of the white one, which is what keeps a pair of buttons from reading as two equal choices.
 *
 * @param tint the glass fill, as on [GlassIconButton]'s `surfaceTint`: [GlassDefaults.Fill] for a
 *   pill inside a screen's own content; a pill floating over raw video (the player's skip-segment
 *   offer) passes a dark fill instead, since there is no backdrop there to pull down.
 * @param loading as on [PrimaryPillButton]: swaps the leading icon for an inline spinner while the
 *   action is in flight (Settings' sign-out, waiting on the server). The caller still disables the
 *   button.
 * @param progress the determinate sibling of [loading]: `0f..1f` draws a progress *ring* in the
 *   leading slot instead of a spinner, `null` leaves the slot to [leadingIcon]. It exists for the
 *   detail screen's wide Download pill, which used to be a static ghost pill while the phone's
 *   circular button showed a live ring — the same transfer, reported on one layout and not the
 *   other (audit 2026-08-08, UI-4). [loading] wins if both are set: an indeterminate wait is a
 *   stronger statement than a stale fraction.
 * @param leadingIconTint colours the leading glyph alone, leaving the label at the pill's own
 *   content colour — the `tint` [GlassIconButton] has, for the same reason: a finished download's
 *   glyph goes accent while the word beside it stays plain.
 */
@Composable
fun GhostPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
    leadingIcon: ImageVector? = null,
    tint: Color = GlassDefaults.Fill,
    loading: Boolean = false,
    progress: Float? = null,
    leadingIconTint: Color? = null,
) {
    PillFrame(
        onClick = onClick,
        enabled = enabled,
        height = if (small) Dimens.PillHeightSmall else Dimens.PillHeight,
        // The glass is a *surface* rather than a container colour: the fill is a blurred backdrop
        // sample, not a colour, so it has to be a modifier on the drawn pill and let whatever is
        // underneath show through.
        surface = Modifier.glassSurface(shape = CircleShape, borderColor = GlassDefaults.GhostBorder, tint = tint),
        contentPadding = pillContentPadding(small),
        contentColor = if (enabled) GhostPillContent else GhostPillDisabledContent,
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
 * A circular glass button holding a single glyph — the refresh's only icon-button shape, used for
 * back, close, search, cast, sort and the rest of the app's chrome.
 *
 * @param size diameter of the *drawn* circle: 36dp in dense chrome, 44dp where the button stands
 *   alone as a primary-sized affordance. The glyph stays 18dp either way, so the two sizes differ in
 *   weight rather than in reach — the button always reserves [Dimens.MinTouchTarget] around whatever
 *   it draws (see this file's header).
 * @param surfaceTint the glass fill. [GlassDefaults.Fill] for a button inside a screen's own
 *   content; chrome floating over arbitrary artwork passes [GlassDefaults.ChromeFill].
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.PillHeightSmall,
    tint: Color = GlassIconTint,
    surfaceTint: Color = GlassDefaults.Fill,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier.size(Dimens.MinTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // The click target sits *inside* the glass so the ripple is clipped to the circle the
            // user can see, rather than to the invisible frame around it.
            //
            // `mergeDescendants` is not about this button's own glyph (there is only one): a
            // semantics node that merges its descendants is not itself swallowed by an ancestor
            // that merges, so the Play button inside an episode row — a row that is now one merged
            // node — survives as its own stop with its own action (audit A11Y-05).
            modifier =
                Modifier
                    .size(size)
                    .glassSurface(shape = CircleShape, tint = surfaceTint)
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

/** How much of its alpha a glass glyph keeps once the button is disabled. */
private const val DISABLED_GLYPH_FACTOR = 0.45f

/**
 * The frame both pills are drawn in: the visual at [height] or taller, centred inside an invisible
 * interactive area at least [Dimens.MinTouchTarget] tall.
 *
 * `propagateMinConstraints` is what keeps the two honest about width. A caller's `fillMaxWidth()`
 * or `weight(1f)` arrives as a *minimum* width on this box, and propagating it means the drawn pill
 * fills the same width the caller asked for instead of hugging its label in the middle of an empty
 * frame; a caller that constrains nothing propagates a zero minimum, and the pill hugs its content
 * as it always did.
 *
 * [height] is a **floor, not a cap** (DECISIONS.md 2026-08-05, accessibility audit SCALE-02). It
 * used to be `requiredHeight`, which pins both ends of the range, and at accessibility font scales
 * ≥1.5 that vertically clipped the label inside every primary and ghost button in the app — a
 * button you cannot read defeats the design the fixed height was protecting (WCAG 1.4.4). At
 * fontScale 1.0 nothing moves: the content is shorter than [height], so the pill still draws at
 * exactly the size the design specifies.
 *
 * It is `requiredHeightIn(min = …)` rather than the more obvious `defaultMinSize(minHeight = …)`
 * because of the `propagateMinConstraints` above: the box's own 48dp minimum reaches this row as a
 * non-zero `minHeight`, and `defaultMinSize` applies only when the incoming minimum is zero — it
 * would silently do nothing here and every pill would draw at the 48dp touch frame's height
 * instead of its own 44dp/36dp. `requiredHeightIn` replaces the incoming height range with
 * `[height, ∞)`, which is precisely "the design height, or taller if the text needs it".
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

/**
 * What a pill says about itself while its action is in flight.
 *
 * The busy pill is disabled — that is what stops a second tap — and a disabled control with a
 * spinner in it announced only "disabled", which is the one thing that does not explain the wait
 * (accessibility audit 2026-08-05, M8). "Busy" is a *state*, not a name, so it goes in
 * `stateDescription` and leaves the label ("Sign in") intact.
 */
@Composable
private fun busyStateDescription(loading: Boolean): String? = stringResource(R.string.state_busy).takeIf { loading }

private fun pillContentPadding(small: Boolean): PaddingValues =
    PaddingValues(horizontal = if (small) PillHorizontalPaddingSmall else PillHorizontalPadding)

/**
 * A pill's glyph and label, emitted straight into [PillFrame]'s row — the gap between them is that
 * row's arrangement rather than a nested one, so a pill that fills its width centres the pair.
 */
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
