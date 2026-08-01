package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Container of a primary pill. Not `colorScheme.primary` — see the file KDoc. */
private val PrimaryPillContainer = Color.White

/** Content on that white fill: the app background colour, for maximum contrast. */
private val PrimaryPillContent = Color(0xFF101010)

/** Disabled primary fill — present enough to hold the button's shape, too faint to invite a tap. */
private val PrimaryPillDisabledContainer = Color.White.copy(alpha = 0.07f)

private val PrimaryPillDisabledContent = Color.White.copy(alpha = 0.35f)

/** Content colour of a ghost pill and its disabled counterpart. */
private val GhostPillContent = Color.White

private val GhostPillDisabledContent = Color.White.copy(alpha = 0.35f)

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
 */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(if (small) Dimens.PillHeightSmall else Dimens.PillHeight),
        enabled = enabled,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = PrimaryPillContainer,
                contentColor = PrimaryPillContent,
                disabledContainerColor = PrimaryPillDisabledContainer,
                disabledContentColor = PrimaryPillDisabledContent,
            ),
        contentPadding = pillContentPadding(small),
    ) {
        PillContent(text = text, small = small, leadingIcon = leadingIcon)
    }
}

/**
 * The glass pill that carries a screen's *secondary* action, beside a [PrimaryPillButton].
 *
 * Same geometry as the primary so the two sit level in a row; a glass fill and a white@12% edge
 * instead of the white one, which is what keeps a pair of buttons from reading as two equal choices.
 */
@Composable
fun GhostPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        // The glass goes on the *modifier* rather than through `containerColor`: the fill is a
        // blurred backdrop sample, not a colour, so the button's own container has to be
        // transparent and let the surface underneath it show through.
        modifier =
            modifier
                .height(if (small) Dimens.PillHeightSmall else Dimens.PillHeight)
                .glassSurface(shape = CircleShape, borderColor = GlassDefaults.GhostBorder),
        enabled = enabled,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = GhostPillContent,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = GhostPillDisabledContent,
            ),
        contentPadding = pillContentPadding(small),
    ) {
        PillContent(text = text, small = small, leadingIcon = leadingIcon)
    }
}

/**
 * A circular glass button holding a single glyph — the refresh's only icon-button shape, used for
 * back, close, search, cast, sort and the rest of the app's chrome.
 *
 * @param size diameter of the circle: 36dp in dense chrome, 44dp where the button stands alone as a
 *   primary-sized affordance. The glyph stays 18dp either way, so the two sizes differ in touch
 *   target rather than in weight.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.PillHeightSmall,
    tint: Color = GlassIconTint,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(size).glassSurface(CircleShape),
        enabled = enabled,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = tint,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = tint.copy(alpha = tint.alpha * DISABLED_GLYPH_FACTOR),
            ),
        // A circle this small has no room for Material's default 24dp of button padding; the glyph
        // is centred in the whole thing instead.
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(GlassIconSize),
        )
    }
}

/** How much of its alpha a glass glyph keeps once the button is disabled. */
private const val DISABLED_GLYPH_FACTOR = 0.45f

private fun pillContentPadding(small: Boolean): PaddingValues =
    PaddingValues(horizontal = if (small) PillHorizontalPaddingSmall else PillHorizontalPadding)

@Composable
private fun PillContent(
    text: String,
    small: Boolean,
    leadingIcon: ImageVector?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PillIconGap),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(if (small) PillIconSizeSmall else PillIconSize),
            )
        }
        Text(
            text = text,
            style = if (small) PillLabelSmall else PillLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
