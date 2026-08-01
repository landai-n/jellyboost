package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * @param enabled `false` leaves the chip visible but inert, which is how the detail screen shows
 *   genres: they look like the filters they will one day be, and do nothing today.
 */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor =
        when {
            selected -> ChipSelectedContent
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CHIP_ALPHA)
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
                ).clickable(enabled = enabled, onClick = onClick)
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
            PillChip(text = "Sci-Fi", selected = false, onClick = {}, enabled = false)
            MPillBadge(text = "TV-MA")
        }
    }
}
