package dev.jellyfinnative.app

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.jellyfinnative.core.common.Routes
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.ui.theme.Dimens

/**
 * The app's one piece of chrome for the four top-level destinations: navigation, app actions and
 * the connection status, in a single bar.
 *
 * It replaces the pairing of a per-screen `TopAppBar` with a bottom `NavigationBar` that the app
 * carried until M9 (DECISIONS.md 2026-07-29, "the top bar and the bottom navigation bar are one
 * combined bar"): two bars cost ~140dp of a phone screen between them and mostly repeated each
 * other, since the top bar's title only ever named the selected tab.
 *
 * Insets are this bar's own business — it is the topmost thing drawn on a top-level destination and
 * the app is edge-to-edge, so it pads itself out of the status bar and the screen below it starts
 * under the bar (`AppScaffold`'s inset contract).
 *
 * @param currentDestination selects the tab; `null` while the graph is still settling.
 * @param connectionState decides whether the offline status icon is drawn, and which one.
 * @param onConnectionStatusClick the status icon was tapped — `AppScaffold` explains the state in a
 *   snackbar with the action that fits it.
 */
@Composable
internal fun AppTopBar(
    currentDestination: NavDestination?,
    connectionState: ConnectionState,
    onSelectTab: (Any) -> Unit,
    onConnectionStatusClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSetForceOffline: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            // One subcomposition for the whole bar (not per tab): on a phone the four labels do not
            // fit next to the actions, so narrow windows get icon-only tabs.
            val showLabels = maxWidth >= LabelledTabsMinWidth

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(AppTopBarHeight)
                        .padding(horizontal = Dimens.SpaceExtraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTabs(
                    currentDestination = currentDestination,
                    showLabels = showLabels,
                    onSelectTab = onSelectTab,
                    modifier = Modifier.weight(1f),
                )
                ConnectionStatusAction(
                    status = connectionState.toStatus(),
                    onClick = onConnectionStatusClick,
                )
                AppOverflowMenu(
                    forceOffline = connectionState == ConnectionState.OFFLINE_FORCED,
                    onNavigateToSettings = onNavigateToSettings,
                    onSetForceOffline = onSetForceOffline,
                )
            }
        }
    }
}

/** Height of the bar's content, above whatever the status bar takes. */
private val AppTopBarHeight: Dp = 56.dp

/** Below this the four labels crowd the actions out, so the tabs go icon-only. */
private val LabelledTabsMinWidth: Dp = 560.dp

@Composable
private fun AppTabs(
    currentDestination: NavDestination?,
    showLabels: Boolean,
    onSelectTab: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Home>() == true,
            icon = Icons.Filled.Home,
            label = stringResource(R.string.nav_home),
            showLabel = showLabels,
            onClick = { onSelectTab(Routes.Home) },
        )
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Libraries>() == true,
            icon = Icons.Filled.VideoLibrary,
            label = stringResource(R.string.nav_libraries),
            showLabel = showLabels,
            onClick = { onSelectTab(Routes.Libraries) },
        )
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Search>() == true,
            icon = Icons.Filled.Search,
            label = stringResource(R.string.nav_search),
            showLabel = showLabels,
            onClick = { onSelectTab(Routes.Search) },
        )
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Downloads>() == true,
            icon = Icons.Filled.Download,
            label = stringResource(R.string.nav_downloads),
            showLabel = showLabels,
            onClick = { onSelectTab(Routes.Downloads) },
        )
    }
}

/**
 * One destination, as a pill that fills its share of the bar.
 *
 * `Role.Tab` plus `selectable` is what makes the row announce as "Home, tab, selected" rather than
 * as four unrelated buttons; the label doubles as the icon's content description when it is hidden,
 * so an icon-only bar stays readable to TalkBack.
 */
@Composable
private fun RowScope.AppTab(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
            Modifier
                .weight(1f)
                .heightIn(min = TabMinHeight)
                .clip(RoundedCornerShape(percent = 50))
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ).padding(horizontal = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (showLabel) null else label,
            tint = contentColor,
            modifier = Modifier.size(TabIconSize),
        )
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Dimens.SpaceExtraSmall),
            )
        }
    }
}

private val TabMinHeight: Dp = 48.dp
private val TabIconSize: Dp = 22.dp

/**
 * The offline notice, shrunk from the full-width banner it was until M9 to one icon
 * (DECISIONS.md 2026-07-29, "the offline banner becomes a status icon").
 *
 * Each reason gets its own icon *and* its own content description, so the three states are told
 * apart without reading any text; tapping spells the reason out in a snackbar.
 */
@Composable
private fun ConnectionStatusAction(
    status: ConnectionStatus?,
    onClick: () -> Unit,
) {
    if (status == null) return

    IconButton(onClick = onClick) {
        Icon(
            imageVector = status.icon(),
            contentDescription = stringResource(status.messageRes),
            tint =
                if (status == ConnectionStatus.FORCED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
    }
}

/**
 * The app-wide overflow: the *Offline mode* quick toggle (M6) and the way into Settings (M9).
 *
 * It used to hang off the home screen's own top bar; with one combined bar there is only one place
 * for it, and it is now reachable from every top-level destination instead of from Home alone
 * (DECISIONS.md 2026-07-29).
 */
@Composable
private fun AppOverflowMenu(
    forceOffline: Boolean,
    onNavigateToSettings: () -> Unit,
    onSetForceOffline: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val offlineStateDescription =
        stringResource(if (forceOffline) R.string.state_on else R.string.state_off)

    // The menu must be a direct sibling of the button *inside the same Box*: a DropdownMenu anchors
    // to its layout parent, and without this wrapper the parent was the whole top bar `Row`, which
    // anchored the menu to the bar's left edge instead of dropping from this button.
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.home_more_options),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.settings_offline_mode)) },
                trailingIcon = {
                    // Not clickable itself — the item's own `onClick` already covers the whole row, and
                    // a second handler here would toggle twice on a tap that landed on the switch.
                    //
                    // The switch role and its on/off state are declared on *this* node rather than on
                    // the item's `modifier`: `clickable` merges the semantics of its descendants, so an
                    // ancestor `Modifier.semantics {}` would sit on a node TalkBack never focuses,
                    // leaving the row announced as a plain menu entry with no state.
                    Switch(
                        checked = forceOffline,
                        onCheckedChange = null,
                        modifier =
                            Modifier.semantics {
                                role = Role.Switch
                                stateDescription = offlineStateDescription
                            },
                    )
                },
                onClick = { onSetForceOffline(!forceOffline) },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.home_settings)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onNavigateToSettings()
                },
            )
        }
    }
}

/**
 * What the app bar says about a non-[ConnectionState.ONLINE] connection: the reason, and the one
 * thing the user can do about it.
 *
 * Kept as a plain enum rather than inlined into the composable so the mapping is unit-testable —
 * it is the same "three reasons, three answers" table the M6 banner carried.
 */
internal enum class ConnectionStatus(
    @param:StringRes val messageRes: Int,
    @param:StringRes val actionLabelRes: Int?,
) {
    /** No usable network at all; nothing to retry until one appears. */
    NO_NETWORK(R.string.offline_no_network, null),

    /** Network, but the server did not answer the probe — worth another try. */
    SERVER_UNREACHABLE(R.string.offline_server_unreachable, R.string.offline_retry),

    /** The user asked to be offline; the action turns it back off. */
    FORCED(R.string.offline_forced, R.string.offline_go_online),
}

/** `null` when the app is online — the bar then draws no status icon at all. */
internal fun ConnectionState.toStatus(): ConnectionStatus? =
    when (this) {
        ConnectionState.ONLINE -> null
        ConnectionState.OFFLINE_NO_NETWORK -> ConnectionStatus.NO_NETWORK
        ConnectionState.OFFLINE_SERVER_UNREACHABLE -> ConnectionStatus.SERVER_UNREACHABLE
        ConnectionState.OFFLINE_FORCED -> ConnectionStatus.FORCED
    }

private fun ConnectionStatus.icon(): ImageVector =
    when (this) {
        ConnectionStatus.NO_NETWORK -> Icons.Filled.WifiOff
        ConnectionStatus.SERVER_UNREACHABLE -> Icons.Filled.CloudOff
        ConnectionStatus.FORCED -> Icons.Filled.AirplanemodeActive
    }
