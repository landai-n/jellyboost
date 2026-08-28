package dev.jellyboost.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.player.ui.CastRouteButton

@Immutable
internal data class AppChromeState(
    val connectionState: ConnectionState,
    val hasActiveSyncPlayGroup: Boolean,
)

/**
 * Kept separate from [AppChromeState] because the two have different lifetimes: that is what lets
 * these callbacks be `remember`ed once while the state flows.
 */
@Immutable
internal data class AppChromeActions(
    val onConnectionStatusClick: () -> Unit,
    val onOpenSyncPlayGroups: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onSetForceOffline: (Boolean) -> Unit,
)

/** The four app-wide actions, in bar order: connection status, Cast, SyncPlay groups, overflow. */
@Composable
internal fun AppActions(
    chrome: AppChromeState,
    actions: AppChromeActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ActionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionStatusAction(
            status = chrome.connectionState.toStatus(),
            onClick = actions.onConnectionStatusClick,
        )
        CastRouteButton(
            modifier = Modifier.size(Dimens.MinTouchTarget),
            glassContainer = true,
            size = Dimens.PillHeightSmall,
            surfaceTint = GlassDefaults.ChromeFill,
        )
        SyncPlayGroupsAction(
            hasActiveGroup = chrome.hasActiveSyncPlayGroup,
            onClick = actions.onOpenSyncPlayGroups,
        )
        AppOverflowMenu(
            forceOffline = chrome.connectionState == ConnectionState.OFFLINE_FORCED,
            onNavigateToSettings = actions.onNavigateToSettings,
            onSetForceOffline = actions.onSetForceOffline,
        )
    }
}

/**
 * Zero on purpose: each action's [Dimens.MinTouchTarget] frame around a [Dimens.PillHeightSmall]
 * circle already leaves the 12dp the mocks show, and positive spacing would be added on top of it.
 */
private val ActionGap: Dp = 0.dp

/** Inset of the SyncPlay badge from the corner of its action's frame — back onto the circle. */
private val BadgeInset: Dp = ActionFrameOverhang

/**
 * [AppActions] as the compact layout draws them. The compact chrome's only bar is at the bottom of
 * the window, so the frame floats these over the top-right corner instead of the screens carrying
 * them — which would mean four screens learning about connection state and SyncPlay membership.
 */
@Composable
internal fun AppActionCluster(
    chrome: AppChromeState,
    actions: AppChromeActions,
    modifier: Modifier = Modifier,
) {
    AppActions(
        chrome = chrome,
        actions = actions,
        // `safeDrawing` rather than `statusBars`: in landscape a display cutout is a *horizontal*
        // inset, and status-bar padding alone put the first circle underneath the notch.
        modifier =
            modifier
                .windowInsetsPadding(TopChromeInsets)
                .padding(top = ActionClusterTopGap, end = ActionClusterEndPadding),
    )
}

/** Each reason gets its own icon *and* content description, so the three states are told apart without text. */
@Composable
private fun ConnectionStatusAction(
    status: ConnectionStatus?,
    onClick: () -> Unit,
) {
    if (status == null) return

    GlassIconButton(
        icon = status.icon(),
        contentDescription = stringResource(status.messageRes),
        onClick = onClick,
        tint =
            if (status == ConnectionStatus.FORCED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        surfaceTint = GlassDefaults.ChromeFill,
    )
}

/**
 * The badge is placed by hand rather than by `BadgedBox`, which anchors to its anchor's bounds — the
 * button's invisible 48dp frame — and so floated the dot clear of the circle it belongs to. The
 * state it stands for is already in the button's content description.
 */
@Composable
private fun SyncPlayGroupsAction(
    hasActiveGroup: Boolean,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        GlassIconButton(
            icon = Icons.Filled.Groups,
            contentDescription =
                stringResource(
                    if (hasActiveGroup) R.string.syncplay_groups_action_active else R.string.syncplay_groups_action,
                ),
            onClick = onClick,
            surfaceTint = GlassDefaults.ChromeFill,
        )
        if (hasActiveGroup) {
            Badge(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = -BadgeInset, y = BadgeInset),
            )
        }
    }
}

@Composable
private fun AppOverflowMenu(
    forceOffline: Boolean,
    onNavigateToSettings: () -> Unit,
    onSetForceOffline: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val offlineStateDescription =
        stringResource(if (forceOffline) R.string.state_on else R.string.state_off)

    // A DropdownMenu anchors to its layout parent, so without this Box it dropped from the chrome
    // `Row`'s left edge instead of from this button.
    Box {
        GlassIconButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.home_more_options),
            onClick = { expanded = true },
            surfaceTint = GlassDefaults.ChromeFill,
        )

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.settings_offline_mode)) },
                trailingIcon = {
                    // Not clickable itself: the item's `onClick` covers the row, and a second handler
                    // would toggle twice. The role and state go on *this* node because `clickable`
                    // merges its descendants' semantics — declared on the item they would sit on a
                    // node TalkBack never focuses.
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
                // `DropdownMenuItem`'s own `clickable` sets no role, so this row would announce without
                // one; declared here it is first in the chain and therefore wins.
                modifier = Modifier.semantics { role = Role.Button },
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
