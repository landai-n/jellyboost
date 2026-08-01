package dev.jellyboost.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
 * scrolls under it.
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
                .statusBarsPadding()
                .height(TopNavHeight)
                .padding(horizontal = BarHorizontalPadding),
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

        TopNavTabs(currentDestination = currentDestination, onSelectTab = onSelectTab)

        Spacer(modifier = Modifier.weight(1f))

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
 * The capsule's fill is a flat [GlassDefaults.Fill] rather than a blur: it is already sitting on the
 * glass of its own tabs' container in the mocks, and nesting a second `hazeEffect` inside the
 * buttons it holds would blur an effect rather than the page — see this file's KDoc.
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
                .background(color = GlassDefaults.Fill, shape = CircleShape)
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
) {
    val contentColor = if (selected) SelectedContent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            Modifier
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
