package dev.jellyboost.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The wide layout's navigation: the brand mark, a glass capsule of four labelled tabs, and the
 * app-wide actions, in one 64dp row over the top of the content
 * (DECISIONS.md 2026-08-01, the 2026-refresh chrome).
 *
 * ### Why the row itself is not one big pane of glass
 * The bar *is* glass, but as a set of floating pieces rather than a full-width slab: the tab capsule
 * and each action carry their own blur, and the row between them is transparent. That is what the
 * mocks show, and it is also what keeps the effect cheap and correct — a full-width blurred bar
 * would mean a `hazeEffect` with the tab capsule's and the buttons' own effects nested inside it,
 * and Haze samples a backdrop, not another effect. Nothing is lost: every element the user has to
 * read sits on its own glass, and the gaps between them are exactly where the page below is meant
 * to show through.
 *
 * The row draws over the content rather than above it — `AppScaffold` hands screens the height
 * through `LocalAppChromePadding` so their first row comes to rest just below the bar and the rest
 * scrolls under it. What keeps it *readable* over a bright backdrop is not a background on this row
 * — that would be the nested-effect mistake described above — but the `TopChromeScrim` band
 * `AppScaffold` draws as a sibling underneath it, plus the darker [GlassDefaults.ChromeFill] its
 * pieces are tinted with (DECISIONS.md 2026-08-01, chrome readability).
 *
 * ### Fitting at the breakpoint
 * The row does not scroll and never clips: the tab capsule takes whatever the brand mark and the
 * actions leave (`weight`), and its four tabs share *that* evenly and ellipsise their labels. At
 * 560dp — the narrowest window that gets this bar at all — a full set of four actions leaves the
 * labels heavily abbreviated, which is the intended degradation; before, the trailing actions
 * simply ran off the end of the window, because a `Spacer(weight)` collapses to zero and then the
 * row overflowed.
 *
 * @param currentDestination selects the tab; `null` while the graph is still settling.
 * @param connectionState decides whether the offline status icon is drawn, and which one.
 * @param hasActiveSyncPlayGroup lights the Groups action's badge (M11 Phase 5).
 */
@Composable
internal fun GlassTopNav(
    currentDestination: NavDestination?,
    connectionState: ConnectionState,
    hasActiveSyncPlayGroup: Boolean,
    onSelectTab: (Any) -> Unit,
    onConnectionStatusClick: () -> Unit,
    onOpenSyncPlayGroups: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSetForceOffline: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopChromeInsets)
                .height(TopNavHeight)
                // The trailing edge is corrected for the actions' invisible frame, so the last
                // *circle* sits the same distance from the window edge as the brand mark does.
                .padding(start = BarHorizontalPadding, end = BarHorizontalPadding - ActionFrameOverhang),
        horizontalArrangement = Arrangement.spacedBy(BarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(CoreUiR.drawable.ic_jellyboost_logo),
            // Decorative: the tab beside it already names where the user is, and the app's identity
            // is not a navigable thing here.
            contentDescription = null,
            modifier = Modifier.size(width = BrandMarkWidth, height = BrandMarkHeight),
        )

        // A weighted box rather than the capsule plus a `Spacer(weight)`: the box is what pushes the
        // actions to the end, and it hands the capsule a *maximum* width to fit inside instead of
        // letting it take its intrinsic width and shove them off the edge. The capsule still hugs
        // its tabs whenever there is room, because the box wraps its content.
        Box(modifier = Modifier.weight(1f)) {
            TopNavTabs(currentDestination = currentDestination, onSelectTab = onSelectTab)
        }

        AppActions(
            connectionState = connectionState,
            hasActiveSyncPlayGroup = hasActiveSyncPlayGroup,
            onConnectionStatusClick = onConnectionStatusClick,
            onOpenSyncPlayGroups = onOpenSyncPlayGroups,
            onNavigateToSettings = onNavigateToSettings,
            onSetForceOffline = onSetForceOffline,
        )
    }
}

/**
 * The four destinations as one glass capsule.
 *
 * The capsule's fill is flat rather than a blur: it is already sitting on the glass of its own tabs'
 * container in the mocks, and nesting a second `hazeEffect` inside the buttons it holds would blur
 * an effect rather than the page — see this file's KDoc. The colour is [GlassDefaults.ChromeFill]
 * rather than the white@6% [GlassDefaults.Fill] the mocks specify, because an unselected tab's label
 * is `onSurfaceVariant` — white at 70% — and white-on-white is what the bar looked like whenever the
 * hero behind it happened to be a bright frame.
 *
 * Every tab is weighted so that a capsule with less room than it wants starves all four equally and
 * each ellipsises its own label, rather than measuring them in order and leaving the last one with
 * nothing — the same reasoning `GlassBottomNav.UNSELECTED_ITEM_WEIGHT` records. `fill = false` is
 * what keeps a *roomy* capsule hugging its tabs instead of stretching them across the window.
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
            TopNavTab(
                tab = tab,
                selected = currentDestination.isSelected(tab),
                onClick = { onSelectTab(tab.route) },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * One tab: a 36dp capsule that fills solid white when it is the current destination.
 *
 * `Role.Tab` plus `selectable` is what makes the capsule announce as "Home, tab, selected" rather
 * than as four unrelated buttons, exactly as the combined app bar's tabs did; the label is always
 * drawn, so the icon needs no content description of its own.
 */
@Composable
private fun TopNavTab(
    tab: TopLevelTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) SelectedContent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            modifier
                .height(Dimens.PillHeightSmall)
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
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(TabIconSize),
        )
        Text(
            text = stringResource(tab.labelRes),
            style = TabLabel,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Padding at both ends of the bar — roomier than a phone's, as the mocks' wide layouts are. */
private val BarHorizontalPadding: Dp = 24.dp

/** Gap between the bar's three groups: brand mark, tab capsule, actions. */
private val BarGap: Dp = 12.dp

/** The fin mark's footprint. Its aspect ratio is the vector's, so nothing is squashed. */
private val BrandMarkWidth: Dp = 34.dp

private val BrandMarkHeight: Dp = 29.dp

/** Inset of the tabs from the capsule that holds them. */
private val TabBarPadding: Dp = 4.dp

private val TabGap: Dp = 2.dp

private val TabHorizontalPadding: Dp = 16.dp

private val TabIconGap: Dp = 8.dp

private val TabIconSize: Dp = 18.dp

/** Content on a selected tab's white fill: the app background colour, for full contrast. */
private val SelectedContent = Color(0xFF101010)

private val TabLabel =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
    )

// No `@Preview` for the whole bar: its actions include `CastRouteButton`, which resolves a
// `hiltViewModel()` and therefore cannot render outside an activity. The tab capsule — the part of
// the bar this file actually styles — previews on its own.
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
 * The capsule at the three widths the bar's own weighted box hands it on a 560 / 640 / 740dp window
 * with two app actions showing — 358, 438 and 538dp.
 *
 * This is the fit check for the row: the capsule has to stay *inside* each of those boxes (the
 * dashed-looking edge of the surrounding box is exactly the space it is allowed) with its labels
 * ellipsised rather than its last tab pushed out. The full bar cannot be previewed — its actions
 * include `CastRouteButton`, which resolves a `hiltViewModel()`.
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

/** What the bar's weighted box measures out for the capsule at 560dp, with two actions showing. */
private val TabsBoxAt560: Dp = 358.dp

private val TabsBoxAt640: Dp = 438.dp

private val TabsBoxAt740: Dp = 538.dp
