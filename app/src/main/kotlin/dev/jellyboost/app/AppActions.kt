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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.OverMedia
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

/**
 * Bar order is fixed: connection status, Cast, SyncPlay groups, overflow.
 *
 * @param ground how far the row has travelled from the page's glass to the artwork's — `0` on a
 *   page, `1` floating over full-bleed artwork, and in between only while the two grounds are
 *   crossing. It is a fraction rather than a flag so the circles do not repaint in one frame under
 *   a screen the user is still watching fade.
 */
@Composable
internal fun AppActions(
    chrome: AppChromeState,
    actions: AppChromeActions,
    modifier: Modifier = Modifier,
    ground: Float = 0f,
) {
    val glass = rememberChromeGlass(ground)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ActionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionStatusAction(
            status = chrome.connectionState.toStatus(),
            onClick = actions.onConnectionStatusClick,
            glass = glass,
        )
        CastRouteButton(
            modifier = Modifier.size(Dimens.MinTouchTarget),
            glassContainer = true,
            size = Dimens.PillHeightSmall,
            surfaceTint = glass.fill,
            tint = glass.content,
            borderColor = glass.border,
        )
        SyncPlayGroupsAction(
            hasActiveGroup = chrome.hasActiveSyncPlayGroup,
            onClick = actions.onOpenSyncPlayGroups,
            glass = glass,
        )
        AppOverflowMenu(
            forceOffline = chrome.connectionState == ConnectionState.OFFLINE_FORCED,
            onNavigateToSettings = actions.onNavigateToSettings,
            onSetForceOffline = actions.onSetForceOffline,
            glass = glass,
        )
    }
}

/**
 * The chrome's drawn colours at a given [ground]. One value rather than five call-site branches, so
 * a circle cannot end up with the artwork's fill and the page's glyph — `GhostPillButton`'s
 * half-fix, on the other axis.
 */
@Immutable
internal data class ChromeGlass(
    val fill: Color,
    val border: Color,
    val content: Color,
    val error: Color,
)

@Composable
private fun rememberChromeGlass(ground: Float): ChromeGlass {
    val pageFill = GlassDefaults.ChromeFill
    val pageBorder = GlassDefaults.Hairline
    val pageContent = GlassIconTint
    val pageError = MaterialTheme.colorScheme.error
    return remember(ground, pageFill, pageBorder, pageContent, pageError) {
        chromeGlassAt(
            ground = ground,
            pageFill = pageFill,
            pageBorder = pageBorder,
            pageContent = pageContent,
            pageError = pageError,
        )
    }
}

/** Extracted so `ChromeGlassTest` can pin both ends and the midpoint without a device. */
internal fun chromeGlassAt(
    ground: Float,
    pageFill: Color,
    pageBorder: Color,
    pageContent: Color,
    pageError: Color,
): ChromeGlass =
    ChromeGlass(
        fill = blend(pageFill, OverMedia.ChromeFill, ground),
        border = blend(pageBorder, OverMedia.ChromeBorder, ground),
        content = blend(pageContent, OverMedia.GlassContent, ground),
        error = blend(pageError, OverMedia.ErrorAccent, ground),
    )

/**
 * The two resting states are returned as themselves rather than as a lerp of themselves: `lerp`
 * round-trips through Oklab, so at rest the chrome would sit a rounding error away from the token it
 * is supposed to be drawing.
 */
private fun blend(
    page: Color,
    overMedia: Color,
    ground: Float,
): Color =
    when {
        ground <= 0f -> page
        ground >= 1f -> overMedia
        else -> lerp(page, overMedia, ground)
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
    ground: Float = 0f,
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
        ground = ground,
    )
}

/** Each reason gets its own icon *and* content description, so the three states are told apart without text. */
@Composable
private fun ConnectionStatusAction(
    status: ConnectionStatus?,
    onClick: () -> Unit,
    glass: ChromeGlass,
) {
    if (status == null) return

    GlassIconButton(
        icon = status.icon(),
        contentDescription = stringResource(status.messageRes),
        onClick = onClick,
        tint = if (status == ConnectionStatus.FORCED) glass.content else glass.error,
        surfaceTint = glass.fill,
        borderColor = glass.border,
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
    glass: ChromeGlass,
) {
    Box(contentAlignment = Alignment.Center) {
        GlassIconButton(
            icon = Icons.Filled.Groups,
            contentDescription =
                stringResource(
                    if (hasActiveGroup) R.string.syncplay_groups_action_active else R.string.syncplay_groups_action,
                ),
            onClick = onClick,
            tint = glass.content,
            surfaceTint = glass.fill,
            borderColor = glass.border,
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
    glass: ChromeGlass,
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
            tint = glass.content,
            surfaceTint = glass.fill,
            borderColor = glass.border,
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
