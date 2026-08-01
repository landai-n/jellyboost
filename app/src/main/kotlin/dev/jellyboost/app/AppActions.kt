package dev.jellyboost.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.player.ui.CastRouteButton

// The app-wide actions the combined app bar used to carry, unpicked from it so that both of the
// refresh's navigation layouts can draw the same four: the wide layout's `GlassTopNav` puts them at
// the end of its row, and the compact one floats them over the top-right corner of the content
// ([AppActionCluster]) — a phone has no persistent bar to hang them off any more, and every one of
// them has to stay reachable from every top-level destination.
//
// The behaviour is the old bar's, verbatim; only the containers are new. Each action is a 36dp
// glass circle instead of a bare `IconButton`, which is what makes them legible over the artwork
// they now sit on rather than over an opaque surface.

/**
 * The four app-wide actions, in bar order: connection status, Cast, SyncPlay groups, overflow.
 *
 * A `RowScope` extension rather than a container of its own so a caller decides the spacing and the
 * alignment — the top nav packs them at the end of a 64dp row, the cluster floats them.
 */
@Composable
internal fun RowScope.AppActions(
    connectionState: ConnectionState,
    hasActiveSyncPlayGroup: Boolean,
    onConnectionStatusClick: () -> Unit,
    onOpenSyncPlayGroups: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSetForceOffline: (Boolean) -> Unit,
) {
    ConnectionStatusAction(
        status = connectionState.toStatus(),
        onClick = onConnectionStatusClick,
    )
    // Draws nothing at all unless the device has a Cast stack and something to cast to (M12); it
    // needs no state from here, and takes none. The glass circle goes on the modifier it applies to
    // its own view, so an unavailable Cast leaves no empty circle behind.
    CastRouteButton(
        modifier = Modifier.size(Dimens.PillHeightSmall).glassSurface(CircleShape),
    )
    SyncPlayGroupsAction(
        hasActiveGroup = hasActiveSyncPlayGroup,
        onClick = onOpenSyncPlayGroups,
    )
    AppOverflowMenu(
        forceOffline = connectionState == ConnectionState.OFFLINE_FORCED,
        onNavigateToSettings = onNavigateToSettings,
        onSetForceOffline = onSetForceOffline,
    )
}

/**
 * [AppActions] as the compact layout draws them: a small right-aligned cluster of glass circles
 * floating in the top-right corner of a top-level screen, with the content passing under it.
 *
 * The refresh's compact chrome is one floating pill at the *bottom* of the window, which leaves the
 * app-wide actions — the offline status, Cast, SyncPlay, Settings and the offline-mode toggle —
 * without the bar they used to hang off. Pushing each of them into the individual screens' own
 * headers would have meant four screens learning about connection state and SyncPlay membership;
 * one cluster owned by the frame keeps every feature exactly as reachable as it was, on every
 * top-level destination, and needs nothing at all from the screens themselves. It is also what the
 * mocks show — the home screen's floating glass circles over the hero are this cluster.
 */
@Composable
internal fun AppActionCluster(
    connectionState: ConnectionState,
    hasActiveSyncPlayGroup: Boolean,
    onConnectionStatusClick: () -> Unit,
    onOpenSyncPlayGroups: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSetForceOffline: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = ActionClusterTopGap, end = ActionClusterEndPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    )
}

/**
 * The way into the dedicated SyncPlay section (M11 Phase 5), badged while this device is a member
 * of a group.
 *
 * The badge is a plain [Badge] dot rather than a participant count: what the icon has to say from
 * here is only "you are in a group right now, wherever that happened" — the count, the name and
 * everything else about it belongs to the section itself once opened.
 */
@Composable
private fun SyncPlayGroupsAction(
    hasActiveGroup: Boolean,
    onClick: () -> Unit,
) {
    BadgedBox(
        badge = { if (hasActiveGroup) Badge() },
    ) {
        GlassIconButton(
            icon = Icons.Filled.Groups,
            contentDescription =
                stringResource(
                    if (hasActiveGroup) R.string.syncplay_groups_action_active else R.string.syncplay_groups_action,
                ),
            onClick = onClick,
        )
    }
}

/**
 * The app-wide overflow: the *Offline mode* quick toggle (M6) and the way into Settings (M9).
 *
 * It used to hang off the home screen's own top bar; since M9 there has been one place for it, and
 * it is reachable from every top-level destination rather than from Home alone
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
    // to its layout parent, and without this wrapper the parent was the whole chrome `Row`, which
    // anchored the menu to the bar's left edge instead of dropping from this button.
    Box {
        GlassIconButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.home_more_options),
            onClick = { expanded = true },
        )

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
