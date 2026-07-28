package dev.jellyfinnative.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.feature.home.HomeScreen

/**
 * The `Routes.Home` destination.
 *
 * Hosts a bare-bones [Scaffold] with a title bar and an overflow menu carrying the two settings
 * that exist before the Settings screen does: *Offline mode* (M6) and *Sign out* (M1). Both move
 * behind the top-bar avatar at M9 (docs/PLAN.md, "Screens" → Settings; DECISIONS.md 2026-07-28
 * "temporary Home placeholder with sign-out lives in `:app`" and "M6: force-offline toggle lives in
 * the home overflow menu"). The bottom navigation bar and the offline banner live one level up, in
 * [AppScaffold].
 *
 * @param onItemClick a row item was tapped — pushes `Routes.ItemDetail`.
 * @param onLibraryClick a *My Media* card was tapped — pushes `Routes.LibraryGrid`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRoute(
    onSignOut: () -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    onLibraryClick: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = { HomeOverflowMenu(onSignOut = onSignOut) },
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
private fun HomeOverflowMenu(onSignOut: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()
    val forceOffline = connectionState == ConnectionState.OFFLINE_FORCED

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
                // Not clickable itself — the whole row toggles, which is the larger target.
                Switch(checked = forceOffline, onCheckedChange = null)
            },
            onClick = { connectionViewModel.setForceOffline(!forceOffline) },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.home_sign_out)) },
            leadingIcon = {
                Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            },
            onClick = {
                expanded = false
                onSignOut()
            },
        )
    }
}
