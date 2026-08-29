package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.OverMedia
import dev.jellyboost.core.ui.theme.pageInk

private const val CHIP_FILL_ALPHA = 0.05f

private val ChipFill: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = CHIP_FILL_ALPHA)

/** The selected chip is the brand pill's inversion, same as `JellyfinButtons.PrimaryPillContainer`. */
private val ChipSelectedFill: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground

private val ChipSelectedContent: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

private val ChipHorizontalPadding = 14.dp

private val ChipVerticalPadding = 7.dp

private val ChipLabel =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
    )

/**
 * One value for both schemes: it *replaces* `onSurfaceVariant`'s own alpha rather than scaling it,
 * so the label is white@70% on the dark chip well (8.87:1) and `#1F2330`@70% on the light one
 * (5.23:1).
 */
private const val DISABLED_CHIP_ALPHA = 0.7f

private val MPillHorizontalPadding = 8.dp

private val MPillVerticalPadding = 2.dp

/**
 * Not an M3 `FilterChip`: the selected state here is a full colour inversion, which
 * `FilterChipDefaults` expresses only as a container tint plus the leading tick the mocks drop.
 *
 * `selectable`, not `clickable` — a plain click node leaves a filter rail's on/off state invisible
 * to a screen reader. A chip that is *never* interactive is [InfoPillChip], not this with
 * `enabled = false`.
 */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ChipFrame(modifier = modifier) {
        ChipSurface(
            text = text,
            selected = selected,
            contentAlpha = if (enabled) 1f else DISABLED_CHIP_ALPHA,
            interaction =
                Modifier.selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
        )
    }
}

/**
 * For a chip that opens something rather than toggling: as a [PillChip] it would announce "not
 * selected" forever, a state the user cannot change.
 *
 * @param overMedia the chip is inside a copy lockup drawn on artwork (the detail hero's origin
 *   chips), which is dark-scrimmed in both schemes — see [OverMedia].
 */
@Composable
fun ActionPillChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    overMedia: Boolean = false,
) {
    ChipFrame(modifier = modifier) {
        ChipSurface(
            text = text,
            selected = false,
            contentAlpha = if (enabled) 1f else DISABLED_CHIP_ALPHA,
            overMedia = overMedia,
            interaction =
                Modifier.clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
        )
    }
}

/**
 * Its own component rather than `PillChip(enabled = false)`: a disabled chip announces "disabled",
 * where a genre label was never a control. No click node, no role, no state.
 */
@Composable
fun InfoPillChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    ChipSurface(
        text = text,
        selected = false,
        contentAlpha = DISABLED_CHIP_ALPHA,
        modifier = modifier,
    )
}

/**
 * Height only: a chip's label plus 28dp of padding already clears the minimum width past two
 * characters, and forcing one would visibly change a row of short filters. See `JellyfinButtons.kt`'s
 * header for why the frame is outside the drawn surface.
 */
@Composable
private fun ChipFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.heightIn(min = Dimens.MinTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * @param interaction a parameter rather than something the caller stacks onto [modifier], because
 *   the click node must sit *inside* the clip and fill for the ripple to be the visible capsule.
 */
@Composable
private fun ChipSurface(
    text: String,
    selected: Boolean,
    contentAlpha: Float,
    modifier: Modifier = Modifier,
    overMedia: Boolean = false,
    interaction: Modifier = Modifier,
) {
    val contentColor =
        when {
            selected -> ChipSelectedContent
            overMedia -> OverMedia.GlassContent.copy(alpha = contentAlpha)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
        }
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .then(
                    when {
                        selected -> Modifier.background(color = ChipSelectedFill, shape = CircleShape)
                        overMedia ->
                            Modifier
                                .background(color = OverMedia.GlassFill, shape = CircleShape)
                                .border(GlassDefaults.HairlineWidth, OverMedia.BadgeBorder, CircleShape)

                        else ->
                            Modifier
                                .background(color = ChipFill, shape = CircleShape)
                                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                    },
                ).then(interaction)
                .defaultMinSize(minHeight = Dimens.PillHeightSmall)
                .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ChipLabel,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Never interactive and never coloured: a label whose box keeps it out of the metadata sentence.
 *
 * @param overMedia the certificate as a hero or detail lockup draws it — on artwork, which is
 *   dark-scrimmed in both schemes, so the label and its box are [OverMedia]'s and not the page's.
 */
@Composable
fun MPillBadge(
    text: String,
    modifier: Modifier = Modifier,
    overMedia: Boolean = false,
) {
    Text(
        text = text,
        style = JellyfinTypeExtras.MPill,
        color = if (overMedia) OverMedia.Meta else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier =
            modifier
                .border(
                    width = GlassDefaults.HairlineWidth,
                    color = if (overMedia) OverMedia.BadgeBorder else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(Dimens.MPillRadius),
                ).padding(horizontal = MPillHorizontalPadding, vertical = MPillVerticalPadding),
    )
}

@Preview(name = "PillChip", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun PillChipPreview() {
    JellyfinTheme {
        Row(
            modifier = Modifier.padding(Dimens.SpaceLarge),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillChip(text = "Unwatched", selected = true, onClick = {})
            PillChip(text = "Favourites", selected = false, onClick = {})
            ActionPillChip(text = "Filters", onClick = {})
            InfoPillChip(text = "Sci-Fi")
            MPillBadge(text = "TV-MA")
        }
    }
}
