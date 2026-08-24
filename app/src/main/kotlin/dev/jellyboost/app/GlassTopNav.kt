package dev.jellyboost.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.res.painterResource
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
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The wide layout's navigation: brand mark, a glass capsule of tabs, and the app-wide actions.
 *
 * The row is floating pieces of glass rather than one full-width slab, and must stay that way: a
 * blurred bar would nest the capsule's and the buttons' own `hazeEffect`s inside a third, and Haze
 * samples a backdrop, not another effect. Readability over a bright backdrop comes from
 * `AppScaffold`'s `TopChromeScrim` sibling, never from a background on this row.
 *
 * Only the *selected* tab carries a label: labels on all four never fit a portrait tablet in a wordy
 * locale. The weights are the backstop, so a starved capsule ellipsises that one label instead of
 * running the trailing actions off the end of the window.
 */
@Composable
internal fun GlassTopNav(
    currentDestination: NavDestination?,
    chrome: AppChromeState,
    actions: AppChromeActions,
    onSelectTab: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopChromeInsets)
                // Minimum, not fixed: at accessibility font scales the tab label outgrows the height
                // the design draws, and a hard `height` clipped it.
                .heightIn(min = TopNavHeight)
                // Corrected for the actions' invisible frame, so the last *circle* — not its frame —
                // sits as far from the window edge as the brand mark does.
                .padding(start = BarHorizontalPadding, end = BarHorizontalPadding - ActionFrameOverhang),
        horizontalArrangement = Arrangement.spacedBy(BarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(CoreUiR.drawable.ic_jellyboost_logo),
            // Decorative: the tab beside it already names where the user is.
            contentDescription = null,
            modifier = Modifier.size(width = BrandMarkWidth, height = BrandMarkHeight),
        )

        // A weighted box, not capsule + `Spacer(weight)`: it hands the capsule a *maximum* width
        // instead of letting the capsule's intrinsic width shove the actions off the edge.
        Box(modifier = Modifier.weight(1f)) {
            TopNavTabs(currentDestination = currentDestination, onSelectTab = onSelectTab)
        }

        AppActions(chrome = chrome, actions = actions)
    }
}

/**
 * The fill is flat rather than a blur (nesting a `hazeEffect` inside the buttons' own would blur an
 * effect) and is [GlassDefaults.ChromeFill] rather than the mocks' white@6% [GlassDefaults.Fill],
 * because an unselected label is white@70% and read as white-on-white over a bright frame.
 *
 * `fill = false` is what keeps a roomy capsule hugging its tabs instead of stretching them.
 */
@Composable
private fun TopNavTabs(
    currentDestination: NavDestination?,
    onSelectTab: (Any) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(color = GlassDefaults.ChromeFill, shape = CircleShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                .padding(TabBarPadding),
        horizontalArrangement = Arrangement.spacedBy(TabGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopLevelTab.entries.forEach { tab ->
            val selected = currentDestination.isSelected(tab)
            TopNavTab(
                tab = tab,
                selected = selected,
                onClick = { onSelectTab(tab.route) },
                // The labelled tab is deliberately NOT weighted: an equal slice with the icon-only
                // ones ellipsises its label on a portrait tablet. Same rule as
                // `GlassBottomNav.UNSELECTED_ITEM_WEIGHT`.
                modifier = if (selected) Modifier else Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * `Role.Tab` plus `selectable` is what announces "Home, tab, selected" rather than four unrelated
 * buttons. The label and the icon's content description trade places, so an icon-only tab still
 * names itself to TalkBack.
 */
@Composable
private fun TopNavTab(
    tab: TopLevelTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor =
        if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            modifier
                // Minimum, not fixed: the pill leaves under 4dp of slack around its label at
                // fontScale 1.0 and none once font padding is added, so a fixed height clipped it.
                .heightIn(min = Dimens.PillHeightSmall)
                .clip(CircleShape)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .background(
                    color = if (selected) Color.White else Color.Transparent,
                    shape = CircleShape,
                ).padding(horizontal = TabHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(TabIconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = if (selected) null else stringResource(tab.labelRes),
            tint = contentColor,
            modifier = Modifier.size(TabIconSize),
        )
        if (selected) {
            Text(
                text = stringResource(tab.labelRes),
                style = TabLabel,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val BarHorizontalPadding: Dp = 24.dp

private val BarGap: Dp = 12.dp

/** The mark's footprint, at the vector's own aspect ratio. */
private val BrandMarkWidth: Dp = 34.dp

private val BrandMarkHeight: Dp = 29.dp

private val TabBarPadding: Dp = 4.dp

private val TabGap: Dp = 2.dp

private val TabHorizontalPadding: Dp = 16.dp

private val TabIconGap: Dp = 8.dp

private val TabIconSize: Dp = 18.dp

private val TabLabel =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
    )

// No `@Preview` for the whole bar: its actions include `CastRouteButton`, which resolves a
// `hiltViewModel()` and cannot render outside an activity.
@Preview(name = "GlassTopNav tabs", showBackground = true, backgroundColor = 0xFF101010, widthDp = 520)
@Composable
private fun TopNavTabsPreview() {
    JellyfinTheme {
        Row(modifier = Modifier.padding(BarHorizontalPadding)) {
            TopNavTabs(currentDestination = null, onSelectTab = {})
        }
    }
}

/**
 * The fit check for the row: the capsule must stay *inside* each box — the space the bar's weighted
 * box allows it at a 560 / 640 / 740dp window — rather than push its last tab out. No tab is
 * selected here, so the widest real state is one label wider than what this shows.
 */
@Preview(
    name = "GlassTopNav tabs — 560/640/740dp windows",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 560,
)
@Composable
private fun TopNavTabsFitPreview() {
    JellyfinTheme {
        Column(
            modifier = Modifier.padding(Dimens.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
            listOf(TabsBoxAt560, TabsBoxAt640, TabsBoxAt740).forEach { width ->
                Box(modifier = Modifier.width(width)) {
                    TopNavTabs(currentDestination = null, onSelectTab = {})
                }
            }
        }
    }
}

/** Measured: what the bar's weighted box leaves the capsule at a 560dp window, with two actions showing. */
private val TabsBoxAt560: Dp = 358.dp

private val TabsBoxAt640: Dp = 438.dp

private val TabsBoxAt740: Dp = 538.dp
