package dev.jellyboost.feature.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.network.model.DiscoveredServer
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
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
 * How far down the screen the accent halo behind the auth content reaches. Tall enough to sit
 * behind the whole branded header on a phone, short enough that the form below stays on the flat
 * `#101010` background.
 */
private val AuthGlowHeight = 420.dp

/** Logo size in the ServerSetup hero — the app's first impression, so it gets real space. */
private val HeroLogoSize = 88.dp

/** Logo size next to a screen title (Login), where it is a brand cue rather than the subject. */
internal val InlineLogoSize = 36.dp

/** Diameter of the leading badge on a discovered-server card. */
private val ServerBadgeSize = 40.dp

/**
 * Minimum window width for the side-by-side auth layout (branding pane + form pane). Matches the
 * Material "expanded" width class; below it, or in portrait, the two stack in one column.
 */
private val AuthTwoPaneMinWidth = 840.dp

/**
 * First screen of the app: pick a Jellyfin server, either from the local-network announcements or
 * by typing an address (docs/PLAN.md, "ServerSetup").
 *
 * Visually it is a branded landing screen: the gradient Jellyboost mark and wordmark sit in an
 * accent halo at the top, the servers found on the network are offered as tappable cards, and the
 * manual address entry is grouped into its own panel below them.
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
    AuthScreenScaffold(
        header = { BrandHero() },
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.server_setup_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (state.sessionWasLost) {
            // Above the form, not in the error slot below it: this is why the screen is here at
            // all, and it must not be mistaken for the last connection attempt having failed.
            SessionLostBanner()
        }

        DiscoveredServersSection(
            servers = state.discoveredServers,
            isDiscovering = state.isDiscovering,
            onServerClick = onDiscoveredServerClick,
        )

        ManualAddressSection(
            state = state,
            onAddressChange = onAddressChange,
            onConnect = onConnect,
        )

        state.error?.let { error -> AuthErrorBlock(message = error) }

        Spacer(modifier = Modifier.height(Dimens.SpaceLarge))
    }
}

/** Logo, wordmark and welcome line — the app's identity, centred above the form. */
@Composable
private fun BrandHero() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        // Decorative here: the wordmark right below already says "Jellyboost" to a screen reader.
        JellyboostLogo(size = HeroLogoSize, contentDescription = null)
        Text(
            text = stringResource(R.string.auth_app_name),
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    brush = JellyfinGradients.Accent,
                    fontWeight = FontWeight.Bold,
                ),
        )
        Text(
            text = stringResource(R.string.server_setup_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The gradient fin mark, drawn from the shared vector so every auth surface uses one geometry.
 *
 * @param contentDescription `null` wherever the wordmark is already spelled out next to the logo.
 */
@Composable
internal fun JellyboostLogo(
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ic_jellyboost_logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/** Why the user is back on this screen: an error-tinted panel, distinct from a failed attempt. */
@Composable
private fun SessionLostBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceLarge),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.server_setup_session_lost),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
            DiscoveredServerCard(server = server, onClick = { onServerClick(server) })
        }

        when {
            isDiscovering ->
                HintRow(text = stringResource(R.string.server_setup_discovering), showSpinner = true)

            servers.isEmpty() ->
                HintRow(text = stringResource(R.string.server_setup_no_servers_found), showSpinner = false)
        }
    }
}

/** One announced server, presented as a tappable card: brand badge, name/address, chevron. */
@Composable
private fun DiscoveredServerCard(
    server: DiscoveredServer,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(ServerBadgeSize)
                        .clip(CircleShape)
                        .background(JellyfinGradients.AccentDiagonal),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
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
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Typing an address by hand, grouped into a panel so it reads as the alternative to the cards. */
@Composable
private fun ManualAddressSection(
    state: ServerSetupUiState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(Dimens.SpaceLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
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
 * The frame both auth screens share: full-bleed dark background with a faint accent halo behind
 * the top of the content, insets handled.
 *
 * Portrait (and any window narrower than [AuthTwoPaneMinWidth]) stacks [header] above [content]
 * in one scrolling column. A landscape tablet instead puts them side by side — the branded
 * identity on the left, the form on the right — because stacked they overflow the short viewport
 * and the last actions end up cropped below the fold.
 */
@Composable
internal fun AuthScreenScaffold(
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTwoPane = maxWidth >= AuthTwoPaneMinWidth && maxWidth > maxHeight

            // Drawn under the insets on purpose: the halo bleeding behind the status bar is what
            // makes it read as part of the background rather than as a banner. Side by side, the
            // halo hangs over the branding pane instead of the empty centre (claude.ai/design,
            // "Login (landscape tablet)").
            Box(
                modifier =
                    if (isTwoPane) {
                        Modifier
                            .fillMaxSize()
                            .background(JellyfinGradients.BrandGlowSide)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(AuthGlowHeight)
                            .align(Alignment.TopCenter)
                            .background(JellyfinGradients.BrandGlow)
                    },
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (isTwoPane) {
                    // Capped and centred as a pair: two full-half panes on a wide screen leave a
                    // dead void in the middle and the content stranded at the edges.
                    Row(
                        modifier =
                            Modifier
                                .widthIn(max = AuthContentMaxWidth * 2)
                                .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
                    ) {
                        AuthPane(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            alignment = Alignment.Center,
                            content = header,
                        )
                        PaneRule()
                        AuthPane(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            alignment = Alignment.Center,
                            content = content,
                        )
                    }
                } else {
                    AuthPane {
                        header()
                        content()
                    }
                }
            }
        }
    }
}

/** Where the hairline between the two panes starts and stops fading, as a fraction of its run. */
private const val PANE_RULE_FADE_FRACTION = 0.2f

/** Vertical inset that keeps the pane hairline clear of the window edges. */
private val PaneRuleVerticalInset = 64.dp

/** Hairline between the branding and form panes, fading out toward both ends. */
@Composable
private fun PaneRule() {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .padding(vertical = PaneRuleVerticalInset)
                .width(1.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        PANE_RULE_FADE_FRACTION to outline,
                        1f - PANE_RULE_FADE_FRACTION to outline,
                        1f to Color.Transparent,
                    ),
                ),
    )
}

/**
 * One column of auth content: capped at [AuthContentMaxWidth], placed per [alignment] while it
 * fits the pane, scrolling on its own once it doesn't.
 */
@Composable
private fun AuthPane(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = alignment) {
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
