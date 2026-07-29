package dev.jellyfinnative.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.feature.home.HomeScreen

/**
 * The `Routes.Home` destination.
 *
 * Hosts a bare-bones [Scaffold] with a title bar and an overflow menu holding the *Offline mode*
 * quick toggle (M6) and the entry point to the Settings screen. Settings opens from this menu
 * rather than from a top-bar avatar because the app has no user-avatar asset pipeline at all
 * (DECISIONS.md 2026-07-29, "M9: Settings is opened from the home overflow menu, not a top-bar
 * avatar"); sign-out moved into that screen with it. The bottom navigation bar and the offline
 * banner live one level up, in [AppScaffold].
 *
 * @param onNavigateToSettings the *Settings* menu entry was tapped — pushes `Routes.Settings`.
 * @param onItemClick a row item was tapped — pushes `Routes.ItemDetail`.
 * @param onLibraryClick a *My Media* card was tapped — pushes `Routes.LibraryGrid`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRoute(
    onNavigateToSettings: () -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = { HomeOverflowMenu(onNavigateToSettings = onNavigateToSettings) },
            )
        },
    ) { innerPadding ->
        HomeScreen(
            viewModel = hiltViewModel(),
            onItemClick = onItemClick,
            onLibraryClick = onLibraryClick,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Overflow menu for the home top bar.
 *
 * Reads its own [ConnectionViewModel]: the underlying `ConnectionStateProvider` is a singleton, so
 * this instance and [AppScaffold]'s see the same state without any wiring through the NavHost.
 */
@Composable
private fun HomeOverflowMenu(onNavigateToSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()
    val forceOffline = connectionState == ConnectionState.OFFLINE_FORCED
    val offlineStateDescription =
        stringResource(if (forceOffline) R.string.state_on else R.string.state_off)

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
            onClick = { connectionViewModel.setForceOffline(!forceOffline) },
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
