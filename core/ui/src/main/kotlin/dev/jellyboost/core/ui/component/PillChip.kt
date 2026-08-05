package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** Fill of an unselected chip — fainter than [GlassDefaults.Fill], as chips come in rows. */
private val ChipFill = Color.White.copy(alpha = 0.05f)

/** A selected chip is solid white with dark content, the same emphasis as a primary pill. */
private val ChipSelectedFill = Color.White

private val ChipSelectedContent = Color(0xFF101010)

private val ChipHorizontalPadding = 14.dp

private val ChipVerticalPadding = 7.dp

private val ChipLabel =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
    )

/** How much of its colour a chip keeps when it is shown for information rather than for tapping. */
private const val DISABLED_CHIP_ALPHA = 0.7f

private val MPillHorizontalPadding = 8.dp

private val MPillVerticalPadding = 2.dp

/**
 * The refresh's filter / facet chip: a pill that is either off (glass) or on (solid white).
 *
 * Hand-built rather than an M3 `FilterChip` because the selected state here is a full colour
 * inversion, which `FilterChipDefaults` expresses only as a container tint plus a leading tick —
 * and the tick is exactly what the mocks drop.
 *
 * The chip is `selectable`, not merely `clickable`: on/off is the *whole* point of a filter, and a
 * plain click node made the library rail's state invisible to a screen reader — eleven chips that
 * all announced their own label and nothing else (accessibility audit 2026-08-05, A11Y-06/M3). It
 * also sits inside an invisible [Dimens.MinTouchTarget] frame, the same pattern the pill buttons
 * use: the drawn capsule keeps its 32dp design height and the target around it is 48dp (A11Y-07).
 *
 * A chip that is *never* interactive is [InfoPillChip], not this with `enabled = false`.
 *
 * @param enabled `false` for a filter that cannot be applied right now — it announces itself as
 *   disabled, which is true. It is the wrong tool for a label that was never a control.
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
 * The same pill, drawn for information only — the detail screen's genres.
 *
 * Its own component rather than `PillChip(enabled = false)` because the two states a screen reader
 * hears are not the same thing: a disabled chip announces "disabled", which invites the user to
 * wonder what they did wrong, when the truth is that a genre on a detail page is a *label* and was
 * never going to do anything (accessibility audit 2026-08-05, A11Y-14/M3). This one carries no
 * click node, no role and no state — just its word.
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
 * The invisible 48dp-tall box a tappable chip is centred in.
 *
 * Height only: a chip's width is its label plus 28dp of padding, which clears the minimum on
 * anything longer than two characters, and forcing a minimum width would visibly change a row of
 * short filters. See `JellyfinButtons.kt`'s header for why the frame is outside the drawn surface.
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
 * The capsule itself — identical whether or not anything can be done to it.
 *
 * @param interaction the click node, when there is one. It goes *inside* the clip and the fill so
 *   the ripple is the capsule the user sees rather than the box around it, which is why it is a
 *   parameter here instead of something the caller stacks onto [modifier].
 */
@Composable
private fun ChipSurface(
    text: String,
    selected: Boolean,
    contentAlpha: Float,
    modifier: Modifier = Modifier,
    interaction: Modifier = Modifier,
) {
    val contentColor =
        if (selected) {
            ChipSelectedContent
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
        }
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier.background(color = ChipSelectedFill, shape = CircleShape)
                    } else {
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
 * The mini outlined badge that sits inline with metadata: a content rating, a resolution, a codec.
 *
 * Never interactive and never coloured — it is a label with a box around it, and the box is what
 * keeps "TV-MA" from reading as part of the sentence of dots and years it sits in.
 */
@Composable
fun MPillBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = JellyfinTypeExtras.MPill,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier =
            modifier
                .border(
                    width = GlassDefaults.HairlineWidth,
                    color = MaterialTheme.colorScheme.outline,
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
            InfoPillChip(text = "Sci-Fi")
            MPillBadge(text = "TV-MA")
        }
    }
}
