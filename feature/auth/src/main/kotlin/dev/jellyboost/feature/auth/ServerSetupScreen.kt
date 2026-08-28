package dev.jellyboost.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.network.model.DiscoveredServer
import dev.jellyboost.core.ui.component.ErrorBanner
import dev.jellyboost.core.ui.component.FieldLabel
import dev.jellyboost.core.ui.component.FieldState
import dev.jellyboost.core.ui.component.JellyfinTextField
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.pageInk
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import dev.jellyboost.core.ui.R as CoreUiR

internal val AuthContentMaxWidth = 460.dp

/** Roomier than [Dimens.SpaceExtraLarge] so the form is not flush against the system bars. */
private val AuthContentVerticalPadding = 32.dp

/** The full padding twice over is the difference between the Login screen scrolling and not. */
private val AuthContentVerticalPaddingCompact = 16.dp

/** Tall enough to sit behind the whole header, short enough that the form stays on flat background. */
private val AuthGlowHeight = 420.dp

private val HeroLogoSize = 88.dp

internal val InlineLogoSize = 36.dp

private val ServerBadgeSize = 38.dp

/** The Material expanded width class; below it, or in portrait, the two panes stack. */
private val AuthTwoPaneMinWidth = 840.dp

internal val AuthHeadingStyle =
    TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = (-0.01).em,
    )

internal val AuthErrorTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

private val TaglineStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

private val ServerCardNameStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.W600)

private val ServerCardVerticalPadding = 14.dp

private const val SERVER_BADGE_FILL_ALPHA = 0.14f
private const val SERVER_BADGE_BORDER_ALPHA = 0.30f
private val ServerBadgeBorderWidth = 1.dp
private val ServerBadgeIconSize = 18.dp

private val TrackColor: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.14f)

private val ProgressTrackColor: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.12f)

private val ConnectingProgressHeight = 4.dp

private val AuthPanelInnerGap = 14.dp

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
            style = AuthHeadingStyle,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (state.sessionWasLost) {
            // Above the form, not in the error slot below it: it must not read as the last attempt failing.
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

        state.cleartextWarningHost?.let { host -> CleartextWarningBanner(host = host) }

        state.error?.let { error -> AuthErrorBlock(message = error) }

        Spacer(modifier = Modifier.height(Dimens.SpaceLarge))
    }
}

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
            style = JellyfinTypeExtras.Wordmark,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.server_setup_tagline),
            style = TaglineStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The vector lives in `:core:ui` because `:app` draws the same mark, and a drawable in a feature
 * module is not reachable from outside it.
 */
@Composable
internal fun JellyboostLogo(
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(CoreUiR.drawable.ic_jellyboost_logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

@Composable
private fun SessionLostBanner() {
    ErrorBanner(
        message = stringResource(R.string.server_setup_session_lost),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Error-coloured rather than a softer warning: an unencrypted sign-in over a network the user does
 * not control hands the access token to whoever is on the path. Advisory only — a second press of
 * Connect is the acknowledgement — so the banner is the whole of it.
 */
@Composable
private fun CleartextWarningBanner(host: String) {
    ErrorBanner(
        message = stringResource(R.string.server_setup_cleartext_warning, host),
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Outlined.LockOpen,
    )
}

@Composable
private fun DiscoveredServersSection(
    servers: List<DiscoveredServer>,
    isDiscovering: Boolean,
    onServerClick: (DiscoveredServer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
        Text(
            text = stringResource(R.string.server_setup_discovered_title).uppercase(),
            style = JellyfinTypeExtras.Eyebrow,
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

@Composable
private fun DiscoveredServerCard(
    server: DiscoveredServer,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(GlassDefaults.HairlineWidth, GlassDefaults.PanelHairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.SpaceLarge, vertical = ServerCardVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
        ) {
            val badgeShape = RoundedCornerShape(Dimens.CardCornerRadius)
            Box(
                modifier =
                    Modifier
                        .size(ServerBadgeSize)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = SERVER_BADGE_FILL_ALPHA),
                            shape = badgeShape,
                        ).border(
                            width = ServerBadgeBorderWidth,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = SERVER_BADGE_BORDER_ALPHA),
                            shape = badgeShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(ServerBadgeIconSize),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = ServerCardNameStyle,
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

@Composable
private fun ManualAddressSection(
    state: ServerSetupUiState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    AuthPanel {
        ManualAddressField(
            state = state,
            onAddressChange = onAddressChange,
            onDone = {
                keyboardController?.hide()
                onConnect()
            },
        )

        PrimaryPillButton(
            text = stringResource(R.string.server_setup_connect),
            onClick = {
                keyboardController?.hide()
                onConnect()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canConnect,
        )

        if (state.isConnecting) {
            // One polite live region, so the caption is spoken rather than gone looking for.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(AuthPanelInnerGap),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(ConnectingProgressHeight),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = ProgressTrackColor,
                )
                Text(
                    text = stringResource(R.string.server_setup_connecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Stays *enabled* while the probe runs: disabling a focused field drops accessibility focus with no
 * anchor, throwing a TalkBack user back to the top. [FieldState.InFlight] refuses the keystroke
 * instead, and the ViewModel ignores edits while `isConnecting`.
 */
@Composable
private fun ManualAddressField(
    state: ServerSetupUiState,
    onAddressChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    JellyfinTextField(
        value = state.address,
        onValueChange = onAddressChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = FieldLabel.eyebrow(stringResource(R.string.server_setup_manual_title)),
        state =
            when {
                state.error != null -> FieldState.Error(authErrorText(state.error))
                state.isConnecting -> FieldState.InFlight
                else -> FieldState.Editable
            },
        placeholder = { Text(text = stringResource(R.string.server_setup_address_placeholder)) },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                // Uri alone leaves the IME correction machinery on, and an autocorrected hostname is a typo
                // the user cannot see — the probe just fails.
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/** One polite live region: the scan ends seconds later, with focus somewhere else entirely. */
@Composable
private fun HintRow(
    text: String,
    showSpinner: Boolean,
) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = TrackColor,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AuthPanel(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AuthPanelInnerGap),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(GlassDefaults.HairlineWidth, GlassDefaults.PanelHairline),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.PanelPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * Stacked, the two panes overflow a landscape tablet and the last actions crop below the fold, so
 * an expanded window puts them side by side. The two-pane branch always reports `compact = false`.
 */
@Composable
internal fun AuthScreenScaffold(
    header: @Composable ColumnScope.(compact: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(compact: Boolean) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTwoPane = maxWidth >= AuthTwoPaneMinWidth && maxWidth > maxHeight

            // Material compact width class: below it both header and panel shrink to fit a phone window.
            val compact = maxWidth < 600.dp

            AuthBrandGlow(isTwoPane = isTwoPane)

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (isTwoPane) {
                    // Capped and centred as a pair: two half-panes leave a dead void in the middle.
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
                            compact = false,
                        ) {
                            header(false)
                        }
                        PaneRule()
                        AuthPane(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            alignment = Alignment.Center,
                            compact = false,
                        ) {
                            content(false)
                        }
                    }
                } else {
                    AuthPane(compact = compact) {
                        header(compact)
                        content(compact)
                    }
                }
            }
        }
    }
}

/** Drawn under the insets on purpose: bleeding behind the status bar is what keeps it a background. */
@Composable
private fun BoxScope.AuthBrandGlow(isTwoPane: Boolean) {
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
}

private const val PANE_RULE_FADE_FRACTION = 0.2f

private val PaneRuleVerticalInset = 64.dp

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

/** [compact] trims the pane padding so the single-pane layout fits a phone window without scrolling. */
@Composable
private fun AuthPane(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val verticalPadding = if (compact) AuthContentVerticalPaddingCompact else AuthContentVerticalPadding
    val horizontalPadding = if (compact) Dimens.SpaceLarge else Dimens.SpaceExtraLarge

    Box(modifier = modifier, contentAlignment = alignment) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = AuthContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
            content = content,
        )
    }
}

/**
 * Assertive: the block appears because what the user asked for did not happen, and focus would
 * otherwise stay on the button that apparently did nothing. Plain copy rather than [ErrorBanner],
 * whose washed panel is reserved for [SessionLostBanner] — the two must keep looking different.
 */
@Composable
internal fun AuthErrorBlock(
    message: AuthErrorMessage,
    modifier: Modifier = Modifier,
) {
    Text(
        text = authErrorText(message),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Assertive },
        style = AuthErrorTextStyle,
        color = MaterialTheme.colorScheme.error,
    )
}

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

@Composable
internal fun OnNavigationEvent(
    flow: Flow<Unit>,
    onEvent: () -> Unit,
) {
    OnNavigationEvent<Unit>(flow) { onEvent() }
}

private val PreviewDiscoveredServers =
    listOf(
        DiscoveredServer(id = UUID.randomUUID(), name = "Living Room", address = "http://192.168.1.10:8096"),
        DiscoveredServer(id = UUID.randomUUID(), name = "Office NAS", address = "http://192.168.1.24:8096"),
    )

@Preview(
    name = "ServerSetup — portrait",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 400,
    heightDp = 800,
)
@Composable
private fun ServerSetupPortraitPreview() {
    JellyfinTheme {
        ServerSetupContent(
            state = ServerSetupUiState(discoveredServers = PreviewDiscoveredServers, isDiscovering = false),
            onAddressChange = {},
            onConnect = {},
            onDiscoveredServerClick = {},
        )
    }
}

@Preview(
    name = "ServerSetup — two-pane",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 1000,
    heightDp = 700,
)
@Composable
private fun ServerSetupTwoPanePreview() {
    JellyfinTheme {
        ServerSetupContent(
            state = ServerSetupUiState(discoveredServers = PreviewDiscoveredServers, isDiscovering = false),
            onAddressChange = {},
            onConnect = {},
            onDiscoveredServerClick = {},
        )
    }
}
