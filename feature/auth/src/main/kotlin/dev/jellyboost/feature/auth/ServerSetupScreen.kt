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
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import dev.jellyboost.core.ui.R as CoreUiR

/** Widest the auth forms grow to; keeps them readable on the tablet the project targets. */
internal val AuthContentMaxWidth = 460.dp

/**
 * Top/bottom breathing room for [AuthScreenScaffold]'s content column — deliberately roomier than
 * [Dimens.SpaceExtraLarge] (the design system's largest spacing token) so the form doesn't feel
 * flush against the status/gesture bars on a phone.
 */
private val AuthContentVerticalPadding = 32.dp

/**
 * [AuthContentVerticalPadding] on a compact (phone) window: a typical portrait phone window is
 * short enough that the full 32dp on both the header and the form panel, twice, is the difference
 * between the Login screen fitting without scrolling and it not.
 */
private val AuthContentVerticalPaddingCompact = 16.dp

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
private val ServerBadgeSize = 38.dp

/**
 * Minimum window width for the side-by-side auth layout (branding pane + form pane). Matches the
 * Material "expanded" width class; below it, or in portrait, the two stack in one column.
 */
private val AuthTwoPaneMinWidth = 840.dp

/**
 * Heading atop each screen's primary panel ("Connect to server", "Sign in") — 2026 refresh
 * (DECISIONS.md 2026-08-01). Shared between [ServerSetupScreen] and `LoginScreen` because both
 * live in this module and both want the exact same restyle of what used to be `titleLarge`.
 */
internal val AuthHeadingStyle =
    TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = (-0.01).em,
    )

/** Inline failure copy below an auth form — the refresh's error-text size, shared by both screens. */
internal val AuthErrorTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** The tagline under the ServerSetup wordmark: smaller than any Material body role. */
private val TaglineStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** Discovered-server card name — 15sp/600, a step down from `titleMedium`. */
private val ServerCardNameStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.W600)

/** Vertical padding of a discovered-server card, per the m-surface spec (14×16dp). */
private val ServerCardVerticalPadding = 14.dp

/** Alpha of the discovered-server badge's primary fill / border. */
private const val SERVER_BADGE_FILL_ALPHA = 0.14f
private const val SERVER_BADGE_BORDER_ALPHA = 0.30f
private val ServerBadgeBorderWidth = 1.dp
private val ServerBadgeIconSize = 18.dp

/** Track colour of an inline spinner or progress bar — white held at the refresh's low alpha. */
private val TrackColor = Color.White.copy(alpha = 0.14f)

/** Track colour of the manual-connect progress bar — the refresh calls this one out separately. */
private val ProgressTrackColor = Color.White.copy(alpha = 0.12f)

private val ConnectingProgressHeight = 4.dp

/** Gap inside a manual/sign-in panel, per the m-panel spec (14dp — narrower than [Dimens.SpaceLarge]). */
private val AuthPanelInnerGap = 14.dp

/**
 * First screen of the app: pick a Jellyfin server, either from the local-network announcements or
 * by typing an address (docs/PLAN.md, "ServerSetup").
 *
 * Visually it is a branded landing screen: the Jellyboost mark and wordmark sit in an accent halo
 * at the top, the servers found on the network are offered as tappable cards, and the manual
 * address entry is grouped into its own panel below them.
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
            style = AuthHeadingStyle,
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

        state.cleartextWarningHost?.let { host -> CleartextWarningBanner(host = host) }

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
            // Solid white rather than the accent gradient (DECISIONS.md 2026-08-01): the wordmark
            // is the one piece of brand type the refresh keeps flat, so it reads next to the
            // gradient fin mark instead of competing with it.
            style = JellyfinTypeExtras.Wordmark,
            color = Color.White,
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
 * The gradient fin mark, drawn from the shared vector so every auth surface uses one geometry.
 *
 * The vector itself lives in `:core:ui` rather than here: `:app`'s wide navigation bar draws the
 * same mark, and a drawable in a feature module is not reachable from outside it.
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
        painter = painterResource(CoreUiR.drawable.ic_jellyboost_logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/** Why the user is back on this screen: an error-tinted banner, distinct from a failed attempt. */
@Composable
private fun SessionLostBanner() {
    ErrorBanner(
        message = stringResource(R.string.server_setup_session_lost),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The server answered, but in the clear and from off the local network (audit SEC-10).
 *
 * Below the form rather than above it, unlike [SessionLostBanner]: this is about the attempt that
 * just succeeded, and the next thing the user does about it is press the Connect button directly
 * above. It is advisory — `ServerSetupViewModel` takes that second press as the acknowledgement and
 * goes on to Login — so nothing here is a control; the banner is the whole of it.
 *
 * [ErrorBanner]'s error colouring is deliberate over a softer warning treatment. An unencrypted
 * sign-in over a network the user does not control hands the access token to whoever is on the
 * path, and the screen has no way to know that they are not; a yellow note reads as a formality.
 * The banner is already an assertive live region, which is what makes it reach a TalkBack user who
 * is still on the Connect button.
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
            // The mocks' k-label caption: the same tracked-out uppercase style a field label
            // renders inside JellyfinTextField, reused here for a section heading instead.
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

/** One announced server, presented as a tappable card: brand badge, name/address, chevron. */
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

/** Typing an address by hand, grouped into a panel so it reads as the alternative to the cards. */
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
            // Bar and caption as one polite live region, so "Contacting the server…" is spoken
            // when it appears instead of being a line the user has to go looking for
            // (accessibility audit 2026-08-05, F4). The inner spacing repeats the panel's own
            // [AuthPanelInnerGap] so grouping the two costs no layout change.
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
 * The address field itself: its caption, its three states, and the URI keyboard it asks for.
 *
 * It stays *enabled* while the probe runs (accessibility audit 2026-08-05, F17): disabling a focused
 * field drops accessibility focus with no anchor to fall back to, so a TalkBack user pressing Connect
 * was thrown back to the top of the screen. The field cannot be *changed* mid-probe either —
 * `ServerSetupViewModel` ignores edits while `isConnecting`, which is a stronger guarantee than a
 * greyed-out box. [FieldState.InFlight] says the same thing to the platform: keep the node, keep the
 * name, keep the value, refuse the keystroke. The state-holder guard stays as well; it is the one a
 * JVM test can hold still. The error and in-flight states never overlap — the ViewModel clears the
 * error when a probe starts.
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
        // "Server address" — was the panel's own heading; now the field's own caption,
        // drawn uppercased and *spoken* in sentence case (`FieldLabel.eyebrow`).
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
                // Uri alone still leaves the IME's correction machinery on (the KeyboardOptions
                // default), and an autocorrected hostname is a typo the user cannot see — the
                // probe just fails.
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/**
 * A hint line, optionally preceded by a small spinner.
 *
 * One polite live region covering both states this row has: the discovery caption while the scan
 * runs, and the "nothing announced itself" line that replaces it when the scan ends. That handover
 * is the whole point — it happens seconds after the screen opens, with the user's attention (and
 * accessibility focus) somewhere else entirely, and until the 2026-08-05 audit (F4) it was silent.
 */
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

/**
 * The "m-panel" both auth screens group a form onto: a solid surface, the app's panel hairline, and
 * one interior gutter (claude.ai/design, "Login (landscape tablet)").
 *
 * It was written twice — this screen's manual-address panel and `LoginScreen`'s sign-in card — kept
 * in step by a comment on each saying it matched the other, and they had drifted anyway: the inner
 * gap was [AuthPanelInnerGap] here and `Dimens.SpaceLarge` (16dp) there (audit 2026-08-08, DUP-9).
 * The 14dp wins, because it is the number the spec gives and the one the comments claimed both
 * screens were using; the sign-in card therefore tightens by 2dp between its fields.
 *
 * @param verticalArrangement overridable for a panel whose children space themselves, but the
 *   default is the spec's and is what both current callers want.
 */
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
 * The frame both auth screens share: full-bleed dark background with a faint accent halo behind
 * the top of the content, insets handled.
 *
 * Portrait (and any window narrower than [AuthTwoPaneMinWidth]) stacks [header] above [content]
 * in one scrolling column. A landscape tablet instead puts them side by side — the branded
 * identity on the left, the form on the right — because stacked they overflow the short viewport
 * and the last actions end up cropped below the fold.
 *
 * [header] and [content] both receive the window's compactness so a caller can shrink oversized
 * pieces (e.g. `LoginContent`'s header) instead of just relying on [AuthPane]'s tighter padding.
 * The two-pane branch always reports `false`: the tablet layout is pixel-identical to the
 * approved design regardless of exactly how wide the expanded window is.
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

            // Material's "compact" width class: below it, the branded header and the panel below
            // it are shrunk just enough that the whole screen fits a typical phone window without
            // scrolling (the two-pane branch never sees this — it always passes `false` below).
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

/**
 * The brand halo behind the auth screens.
 *
 * Drawn under the insets on purpose: bleeding behind the status bar is what makes it read as part of
 * the background rather than as a banner. Side by side it hangs over the branding pane instead of
 * the empty centre (claude.ai/design, "Login (landscape tablet)").
 */
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
 *
 * [compact] trims the pane's own padding on a phone-width window — the biggest single chunk of
 * vertical space this screen spends on anything but actual content — so the single-pane layout
 * fits a typical phone window without scrolling.
 */
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
 * Multi-line, error-coloured copy shared by both auth screens.
 *
 * It interrupts. This block appears *because* the thing the user just asked for did not happen, and
 * before the 2026-08-05 accessibility audit (F2/CR-3) nothing said so: focus stayed on the button
 * that had apparently done nothing, and the sentence explaining why sat several swipes away.
 * Assertive rather than polite for the same reason `:core:ui`'s `ErrorBanner` is — the user is about
 * to retype a password into a form that has already rejected it.
 *
 * Kept as plain copy rather than swapped for `ErrorBanner`: the 2026 refresh gives an inline auth
 * failure a bare error-coloured line under the form (DECISIONS.md 2026-08-01), and the banner's
 * washed panel is the treatment reserved for [SessionLostBanner] — the one message that is *not*
 * about the last attempt. The two need to keep looking different; only the announcement was missing.
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
