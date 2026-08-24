package dev.jellyboost.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.popShadow

/**
 * The compact layout's navigation: a floating glass pill carrying the four top-level destinations.
 *
 * The glass is applied *here*, inside the bar, rather than around the `AnimatedVisibility` that shows
 * it: the effect must be attached to the node that draws the glass, or the enter/exit alpha fades a
 * snapshot of the backdrop instead of the bar.
 *
 * Selection is a `Role.Tab` `selectable`, so the row announces as "Home, tab, selected" rather than
 * as four unrelated buttons; both states show their label, so no icon needs a content description.
 */
@Composable
internal fun GlassBottomNav(
    currentDestination: NavDestination?,
    onSelectTab: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A darker tint than ChromeFill: this bar's labels are the smallest text in the chrome and it
    // sits over full-bleed artwork — see [GlassDefaults.BottomNavFill].
    val glass = Modifier.glassSurface(shape = CircleShape, tint = GlassDefaults.BottomNavFill)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = BottomNavMargin)
                // Minimum, not fixed: at accessibility font scales the stacked icon-over-label item
                // outgrows the design height, and a hard `height` clipped the labels.
                .heightIn(min = BottomNavHeight)
                .popShadow(CircleShape)
                .then(glass)
                .padding(horizontal = BarHorizontalPadding),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopLevelTab.entries.forEach { tab ->
            BottomNavItem(
                tab = tab,
                selected = currentDestination.isSelected(tab),
                onClick = { onSelectTab(tab.route) },
            )
        }
    }
}

/**
 * Weighted rather than left to [Arrangement.SpaceAround] so a long-label locale degrades evenly: at
 * 360dp the French labels overflow the pill, and without a weight the row ellipsised the *fourth*
 * label down to a single letter. The selected pill is deliberately not weighted — it hugs its content.
 */
private const val UNSELECTED_ITEM_WEIGHT = 1f

/** The two states are different *shapes*, not just colours — that is the selection indicator. */
@Composable
private fun RowScope.BottomNavItem(
    tab: TopLevelTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(tab.labelRes)
    val base =
        (if (selected) Modifier else Modifier.weight(UNSELECTED_ITEM_WEIGHT))
            .clip(CircleShape)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)

    if (selected) {
        val selectedContent = MaterialTheme.colorScheme.background
        Row(
            modifier =
                base
                    .background(color = Color.White, shape = CircleShape)
                    .padding(horizontal = SelectedHorizontalPadding, vertical = SelectedVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(SelectedIconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                tint = selectedContent,
                modifier = Modifier.size(ItemIconSize),
            )
            Text(
                text = label,
                style = SelectedLabel,
                color = selectedContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column(
            modifier =
                base.padding(horizontal = UnselectedHorizontalPadding, vertical = UnselectedVerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UnselectedIconGap),
        ) {
            // Full white, not `onSurfaceVariant`'s white@70%: over a bright frame the muted white
            // composited under 3:1 even on the darkened fill. Shape is what marks the selection.
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(ItemIconSize),
            )
            Text(
                text = label,
                style = UnselectedLabel,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val BarHorizontalPadding: Dp = 8.dp

/** The same in both states, so the row does not shift as the selection moves. */
private val ItemIconSize: Dp = 20.dp

private val SelectedHorizontalPadding: Dp = 16.dp

private val SelectedVerticalPadding: Dp = 8.dp

private val SelectedIconGap: Dp = 7.dp

private val UnselectedHorizontalPadding: Dp = 8.dp

private val UnselectedVerticalPadding: Dp = 6.dp

private val UnselectedIconGap: Dp = 2.dp

private val SelectedLabel =
    TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.W600,
    )

private val UnselectedLabel =
    TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.W500,
    )

@Preview(name = "GlassBottomNav", showBackground = true, backgroundColor = 0xFF101010, widthDp = 400)
@Composable
private fun GlassBottomNavPreview() {
    JellyfinTheme {
        // No destination is selected without a NavController: the selected capsule is device-only.
        GlassBottomNav(currentDestination = null, onSelectTab = {}, modifier = Modifier.padding(vertical = 20.dp))
    }
}
