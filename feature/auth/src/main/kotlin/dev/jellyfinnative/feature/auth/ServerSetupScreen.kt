package dev.jellyfinnative.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.network.model.DiscoveredServer
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinGradients
import kotlinx.coroutines.flow.Flow

/** Widest the auth forms grow to; keeps them readable on the tablet the project targets. */
internal val AuthContentMaxWidth = 460.dp

/**
 * Top/bottom breathing room for [AuthScreenScaffold]'s content column — deliberately roomier than
 * [Dimens.SpaceExtraLarge] (the design system's largest spacing token) so the form doesn't feel
 * flush against the status/gesture bars on a phone.
 */
private val AuthContentVerticalPadding = 32.dp

/**
 * First screen of the app: pick a Jellyfin server, either from the local-network announcements or
 * by typing an address (docs/PLAN.md, "ServerSetup").
 *
 * @param onNavigateToLogin invoked once an address has been resolved to a usable server.
 */
@Composable
fun ServerSetupScreen(
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ServerSetupViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    OnNavigationEvent(viewModel.navigateToLogin, onNavigateToLogin)

    ServerSetupContent(
        state = uiState,
        onAddressChange = viewModel::onAddressChange,
        onConnect = viewModel::connect,
        onDiscoveredServerClick = { server -> viewModel.connectTo(server.address) },
        modifier = modifier,
    )
}

@Composable
private fun ServerSetupContent(
    state: ServerSetupUiState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDiscoveredServerClick: (DiscoveredServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    AuthScreenScaffold(modifier = modifier) {
        Text(
            text = stringResource(R.string.auth_app_name),
            style = MaterialTheme.typography.titleMedium.copy(brush = JellyfinGradients.Accent),
        )
        Text(
            text = stringResource(R.string.server_setup_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (state.sessionWasLost) {
            // Above the form, not in the error slot below it: this is why the screen is here at
            // all, and it must not be mistaken for the last connection attempt having failed.
            Text(
                text = stringResource(R.string.server_setup_session_lost),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        DiscoveredServersSection(
            servers = state.discoveredServers,
            isDiscovering = state.isDiscovering,
            onServerClick = onDiscoveredServerClick,
        )

        Text(
            text = stringResource(R.string.server_setup_manual_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isConnecting,
            label = { Text(text = stringResource(R.string.server_setup_address_label)) },
            placeholder = { Text(text = stringResource(R.string.server_setup_address_placeholder)) },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        onConnect()
                    },
                ),
        )

        Button(
            onClick = {
                keyboardController?.hide()
                onConnect()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canConnect,
        ) {
            Text(text = stringResource(R.string.server_setup_connect))
        }

        if (state.isConnecting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.server_setup_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let { error -> AuthErrorBlock(message = error) }

        Spacer(modifier = Modifier.height(Dimens.SpaceLarge))
    }
}

@Composable
private fun DiscoveredServersSection(
    servers: List<DiscoveredServer>,
    isDiscovering: Boolean,
    onServerClick: (DiscoveredServer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
        Text(
            text = stringResource(R.string.server_setup_discovered_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        servers.forEach { server ->
            DiscoveredServerRow(server = server, onClick = { onServerClick(server) })
        }

        when {
            isDiscovering ->
                HintRow(text = stringResource(R.string.server_setup_discovering), showSpinner = true)

            servers.isEmpty() ->
                HintRow(text = stringResource(R.string.server_setup_no_servers_found), showSpinner = false)
        }
    }
}

@Composable
private fun DiscoveredServerRow(
    server: DiscoveredServer,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceMedium)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = server.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A hint line, optionally preceded by a small spinner. */
@Composable
private fun HintRow(
    text: String,
    showSpinner: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The frame both auth screens share: full-bleed dark background, insets handled, and a centred
 * column that stays legible on a tablet in landscape while still scrolling on a small phone.
 */
@Composable
internal fun AuthScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = AuthContentMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Dimens.SpaceExtraLarge, vertical = AuthContentVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
                content = content,
            )
        }
    }
}

/** Multi-line, error-coloured copy shared by both auth screens. */
@Composable
internal fun AuthErrorBlock(
    message: AuthErrorMessage,
    modifier: Modifier = Modifier,
) {
    Text(
        text = authErrorText(message),
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Collects a one-shot navigation [flow] for as long as this composable is in composition. */
@Composable
internal fun <T> OnNavigationEvent(
    flow: Flow<T>,
    onEvent: (T) -> Unit,
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(flow) {
        flow.collect { event -> currentOnEvent(event) }
    }
}

/** Convenience overload for event flows carrying no payload. */
@Composable
internal fun OnNavigationEvent(
    flow: Flow<Unit>,
    onEvent: () -> Unit,
) {
    OnNavigationEvent<Unit>(flow) { onEvent() }
}
