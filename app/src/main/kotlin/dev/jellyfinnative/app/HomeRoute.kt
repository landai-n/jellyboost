package dev.jellyfinnative.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.feature.home.HomeScreen

/**
 * The `Routes.Home` destination.
 *
 * Hosts a bare-bones [Scaffold] with a title bar and a sign-out action — a temporary home for
 * sign-out until it moves to Settings at M9, mirroring the note the deleted `HomePlaceholderScreen`
 * carried (see DECISIONS.md, 2026-07-28, "temporary Home placeholder with sign-out lives in
 * `:app`"). The bottom navigation bar and the offline banner (`AppScaffold`) live one level up, in
 * `MainActivity`.
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
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.home_sign_out),
                        )
                    }
                },
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
