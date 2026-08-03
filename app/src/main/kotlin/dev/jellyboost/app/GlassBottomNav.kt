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
 * The compact layout's navigation: a floating glass pill carrying the four top-level destinations
 * (DECISIONS.md 2026-08-01, the 2026-refresh chrome).
 *
 * It is a *floating* bar rather than a docked one — [BottomNavMargin] of clear background on all
 * three sides, a [popShadow] under it, and the page's own content blurred through it — which is
 * what the refresh's mocks show and why the pill does not sit in a `Scaffold` slot: nothing shrinks
 * the screen above it, content scrolls under it, and screens keep their last row clear of it with
 * `LocalAppChromePadding` instead.
 *
 * The blur comes from [glassSurface] reading `LocalHazeState`, which `AppScaffold` provides around
 * the whole frame. It is applied *here*, inside the bar, rather than around the `AnimatedVisibility`
 * that shows it: the effect has to be attached to the node that actually draws the glass, or the
 * enter/exit alpha would fade a snapshot of the backdrop instead of the bar.
 *
 * Selection is a `Role.Tab` `selectable`, exactly as the combined app bar's tabs were, so the row
 * still announces as "Home, tab, selected" rather than as four unrelated buttons; both states show
 * their label, so no icon needs a separate content description.
 *
 * @param currentDestination selects the tab; `null` while the graph is still settling.
 * @param onSelectTab a tab was tapped — the caller navigates with `topLevelNavOptions()`, which is
 *   what makes a re-selection return to that tab's root instead of stacking a second copy.
 */
@Composable
internal fun GlassBottomNav(
    currentDestination: NavDestination?,
    onSelectTab: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = BottomNavMargin)
                // A *minimum*, not a fixed height: at accessibility font scales the stacked
                // icon-over-label item outgrows 60dp, and a hard `height` clipped the labels the
                // bar exists to show. The pill floats, so growing costs nothing but overlap slack.
                .heightIn(min = BottomNavHeight)
                .popShadow(CircleShape)
                // The pill's own darker tint, not ChromeFill: this bar's labels are the smallest
                // text in the chrome and it sits over full-bleed artwork — see [GlassDefaults
                // .BottomNavFill] for the arithmetic.
                .glassSurface(shape = CircleShape, tint = GlassDefaults.BottomNavFill)
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
 * How the three unselected items share whatever the selected pill leaves them.
 *
 * They are weighted rather than laid out by [Arrangement.SpaceAround] alone so that a locale with
 * long destination names degrades evenly instead of starving the last item: at 360dp the French
 * labels ("Bibliothèques", "Téléchargements") overflow the pill, and without a weight the row
 * simply ran out of room and ellipsised the fourth label down to a single letter. With one, every
 * unselected item gets the same width and every label ellipsises by the same amount. The selected
 * pill is deliberately *not* weighted — it hugs its content, as the mocks draw it.
 */
private const val UNSELECTED_ITEM_WEIGHT = 1f

/**
 * One destination in the pill.
 *
 * The two states are different *shapes*, not just different colours: unselected is a stacked
 * icon-over-label column in the muted content colour, selected is a solid white capsule with the
 * icon and a larger label side by side. On a bar this small that difference is what makes the
 * current tab readable at a glance without a separate indicator.
 */
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
        // Content on the selected item's white capsule: the app background colour, for full
        // contrast — the same token `GlassTopNav`'s selected tab uses.
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
            // Full white, not `onSurfaceVariant`'s white@70%: a 10sp label loses too much of its
            // contrast to a translucent ink over blurred artwork — even on the darkened
            // [GlassDefaults.BottomNavFill], the muted white composited under 3:1 on a bright
            // frame. Unselected still reads as unselected by *shape* (stacked column vs the white
            // capsule), which is the distinction this bar was designed around.
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

/** Breathing room between the pill's edge and the first and last item. */
private val BarHorizontalPadding: Dp = 8.dp

/** Glyph size, the same in both states so the row does not shift as the selection moves. */
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
        // No destination is "selected" without a NavController, so the preview shows the resting
        // shape of the bar; the selected capsule is exercised on device.
        GlassBottomNav(currentDestination = null, onSelectTab = {}, modifier = Modifier.padding(vertical = 20.dp))
    }
}
