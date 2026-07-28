package dev.jellyfinnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jellyfinnative.core.network.model.SessionState

/**
 * Throwaway signed-in screen for M1.
 *
 * It exists only so the milestone's definition of done can be walked on a device: it proves the
 * session survived (or was restored), shows the server version and the signed-in user, and gives
 * sign-out somewhere to live. The real Home screen lands in `:feature:home` (M2) and sign-out
 * moves to Settings (M9), at which point this file is deleted.
 * See DECISIONS.md, 2026-07-28, "temporary Home placeholder with sign-out lives in `:app`".
 */
@Composable
internal fun HomePlaceholderScreen(
    session: SessionState.LoggedIn?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    if (session == null) {
                        stringResource(R.string.home_placeholder_signed_in_unknown)
                    } else {
                        stringResource(
                            R.string.home_placeholder_signed_in,
                            session.userName,
                            session.serverName,
                            session.serverVersion ?: stringResource(R.string.home_placeholder_unknown_version),
                        )
                    },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onSignOut) {
                Text(text = stringResource(R.string.home_placeholder_sign_out))
            }
        }
    }
}
