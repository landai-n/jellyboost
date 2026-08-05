package dev.jellyboost.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.network.model.PublicUserInfo
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.component.JellyfinTextField
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import java.util.UUID

private val AvatarSize = 88.dp

/** [AvatarSize] on a compact (phone) window — still a clear tap target, just not the hero size. */
private val AvatarSizeCompact = 64.dp

/**
 * Ring around every public-user avatar: a solid primary ring on the selected profile, a faint
 * neutral one on the rest — per the claude.ai/design "Login (landscape tablet)" card.
 */
private val AvatarRingWidth = 2.dp

/** Breathing room between the ring and the picture it frames, so the ring doesn't crop it. */
private val AvatarRingGap = 3.dp

/** Horizontal gap between avatars in the picker row. */
private val AvatarRowSpacing = 32.dp

/** [AvatarRowSpacing] on a compact (phone) window — the smaller avatars need less air between them. */
private val AvatarRowSpacingCompact = 20.dp

/** Alpha of the unselected avatar ring — a faint neutral edge rather than [GlassDefaults.Hairline]. */
private const val AVATAR_RING_UNSELECTED_ALPHA = 0.10f

/** "WHO'S WATCHING?" — a centred eyebrow, one size up from the shared `JellyfinTypeExtras.Eyebrow`. */
private val WhosWatchingStyle =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.12.em,
    )

/** Server name atop the identity block — bold and tracked in tight, the 2026 refresh's hero type. */
private val ServerNameStyle =
    TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = (-0.02).em,
    )

/** [ServerNameStyle] on a compact (phone) window — same weight and tracking, smaller. */
private val ServerNameStyleCompact = ServerNameStyle.copy(fontSize = 26.sp)

/** Name under a public-user avatar. */
private val AvatarNameStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)

/** "OR" between the sign-in form and Quick Connect. */
private val OrDividerTextStyle =
    TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.1.em,
    )

/** "Use another server" — a text link rather than a full button, styled in the accent colour. */
private val ChangeServerLinkStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)

/** Quick Connect dialog title. */
private val QuickConnectTitleStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.W600)

/** Quick Connect instructions — smaller and tighter than the dialog's default `bodyMedium`. */
private val QuickConnectInstructionStyle = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)

/** One code digit box, per the mocks' "six boxes, not gradient text" treatment. */
private val QuickConnectDigitWidth = 46.dp
private val QuickConnectDigitHeight = 58.dp
private val QuickConnectDigitGap = 8.dp
private val QuickConnectDigitStyle =
    TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.W600,
        // Tabular figures: six digits of varying width would otherwise make the boxes' centring
        // wobble character to character.
        fontFeatureSettings = "tnum",
    )
private val QuickConnectDigitFill = Color.White.copy(alpha = 0.05f)

/**
 * Second screen of the auth flow: sign in to the server ServerSetup resolved, by password or by
 * Quick Connect (docs/PLAN.md, "Login").
 *
 * It carries the same branding as ServerSetup — accent halo, the Jellyboost mark above the server
 * name — so the two screens read as one flow, and it shows the server's real profile pictures for
 * the users it advertises.
 *
 * @param onLoggedIn invoked once a session exists; the NavHost swaps to the signed-in graph.
 * @param onBackToServerSetup invoked when the user picks a different server, or when this screen
 *   was reached without a pending server (e.g. after process death mid-flow).
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onBackToServerSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LoginViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    OnNavigationEvent(viewModel.navigationEvents) { event ->
        when (event) {
            LoginNavigationEvent.LoggedIn -> onLoggedIn()
            LoginNavigationEvent.ServerMissing -> onBackToServerSetup()
        }
    }

    LoginContent(
        state = uiState,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onPublicUserSelected = viewModel::onPublicUserSelected,
        onSignIn = viewModel::signIn,
        onStartQuickConnect = viewModel::startQuickConnect,
        onCancelQuickConnect = viewModel::cancelQuickConnect,
        onChangeServer = {
            viewModel.onLeavingLogin()
            onBackToServerSetup()
        },
        modifier = modifier,
    )
}

@Composable
private fun LoginContent(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPublicUserSelected: (PublicUserInfo) -> Unit,
    onSignIn: () -> Unit,
    onStartQuickConnect: () -> Unit,
    onCancelQuickConnect: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthScreenScaffold(
        header = { compact ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            ) {
                JellyboostLogo(
                    size = InlineLogoSize,
                    contentDescription = stringResource(R.string.auth_logo_description),
                )
                Text(
                    text = state.serverName,
                    style = if (compact) ServerNameStyleCompact else ServerNameStyle,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text =
                        state.serverVersion
                            ?.let { version -> stringResource(R.string.login_server_version, version) }
                            ?: stringResource(R.string.login_server_version_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isLoadingContext) {
                // Bar and caption as one polite live region (accessibility audit 2026-08-05, F4):
                // the screen arrives already loading, and until this the only sign of it was a
                // moving bar. The inner spacing repeats the enclosing pane's own gap, so grouping
                // the two into one node leaves the layout exactly where it was.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.login_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.loginDisclaimer?.let { disclaimer ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = disclaimer,
                        modifier = Modifier.padding(Dimens.SpaceLarge),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.publicUsers.isNotEmpty()) {
                PublicUsersRow(
                    users = state.publicUsers,
                    avatarUrlFor = state::avatarUrlFor,
                    // Selection is derived, not stored: picking a user pre-fills the username,
                    // so the highlighted profile is simply the one the field currently names.
                    selectedName = state.username,
                    onUserSelected = onPublicUserSelected,
                    compact = compact,
                )
            }
        },
        modifier = modifier,
    ) {
        // The whole sign-in form lives on one m-panel (claude.ai/design, "Login (landscape
        // tablet)") — same treatment as ServerSetup's manual-address panel, so the two screens
        // keep reading as one flow.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(GlassDefaults.HairlineWidth, GlassDefaults.PanelHairline),
        ) {
            Column(
                modifier = Modifier.padding(Dimens.PanelPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
            ) {
                LoginFormFields(
                    state = state,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
                    onSignIn = onSignIn,
                    onStartQuickConnect = onStartQuickConnect,
                    onChangeServer = onChangeServer,
                )
            }
        }
    }

    state.quickConnect?.let { quickConnect ->
        QuickConnectDialog(state = quickConnect, onDismiss = onCancelQuickConnect)
    }
}

/** The contents of the sign-in card: title, credential fields, actions. */
@Composable
private fun LoginFormFields(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onStartQuickConnect: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var passwordVisible by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.login_title),
        style = AuthHeadingStyle,
        color = MaterialTheme.colorScheme.onSurface,
    )

    // Both credential fields carry the failure, because a rejected sign-in does not say which of
    // the two was wrong — and a field marked invalid with nothing to say about it is worse than
    // one that repeats the screen's sentence (accessibility audit 2026-08-05, CR-2/F2).
    val errorText = state.error?.let { authErrorText(it) }

    JellyfinTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        // Both credential fields stay enabled while the exchange runs (accessibility audit
        // 2026-08-05, F17): disabling the field a TalkBack user is standing on destroys its node,
        // dropping accessibility focus to the top of the screen at the exact moment the user wants
        // to hear what happened. `LoginViewModel` ignores edits while `isSigningIn`, so "enabled"
        // does not mean "mutable" — what is in flight is what was in the fields when it started.
        // `readOnly` says that to the platform too, so the IME does not offer a keyboard for a
        // field whose contents cannot move.
        readOnly = state.isSigningIn,
        isError = state.error != null,
        label = { Text(text = stringResource(R.string.login_username_label).uppercase()) },
        labelText = stringResource(R.string.login_username_label),
        errorMessage = errorText,
        autofillContentType = ContentType.Username,
        // Autocorrect off for the same reason as the server address field: an IME "fixing" an
        // account name produces a sign-in failure the user cannot see the cause of.
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
    )

    JellyfinTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        // Enabled through the sign-in for the same reason the username field is — see above.
        readOnly = state.isSigningIn,
        isError = state.error != null,
        label = { Text(text = stringResource(R.string.login_password_label).uppercase()) },
        labelText = stringResource(R.string.login_password_label),
        errorMessage = errorText,
        password = true,
        autofillContentType = ContentType.Password,
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    onSignIn()
                },
            ),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector =
                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription =
                        stringResource(
                            if (passwordVisible) R.string.login_hide_password else R.string.login_show_password,
                        ),
                )
            }
        },
    )

    PrimaryPillButton(
        text = stringResource(R.string.login_sign_in),
        onClick = {
            keyboardController?.hide()
            onSignIn()
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canSignIn,
        loading = state.isSigningIn,
    )

    state.error?.let { error -> AuthErrorBlock(message = error) }

    if (state.quickConnectEnabled) {
        OrDivider()
        GhostPillButton(
            text = stringResource(R.string.login_quick_connect),
            onClick = onStartQuickConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSigningIn,
        )
    }

    TextButton(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.login_change_server),
            style = ChangeServerLinkStyle,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** `——— OR ———`: a centred label with a hairline fading out to each side. */
@Composable
private fun OrDivider() {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = outline)
        Text(
            text = stringResource(R.string.login_or).uppercase(),
            style = OrDividerTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = outline)
    }
}

@Composable
private fun PublicUsersRow(
    users: List<PublicUserInfo>,
    avatarUrlFor: (PublicUserInfo) -> String?,
    selectedName: String,
    onUserSelected: (PublicUserInfo) -> Unit,
    compact: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Text(
            text = stringResource(R.string.login_public_users_title).uppercase(),
            modifier = Modifier.fillMaxWidth(),
            style = WhosWatchingStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // The Box centres the row while it fits; once there are enough users to overflow, the
        // row takes the full width and scrolls instead.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                // `selectableGroup()` is what turns N independent circles into one set of radio
                // buttons, so TalkBack can say "2 of 3" and a user knows how many profiles there
                // are without swiping to the end (accessibility audit 2026-08-05, F6).
                modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement =
                    Arrangement.spacedBy(if (compact) AvatarRowSpacingCompact else AvatarRowSpacing),
            ) {
                users.forEach { user ->
                    PublicUserAvatar(
                        user = user,
                        avatarUrl = avatarUrlFor(user),
                        selected = user.name == selectedName,
                        onClick = { onUserSelected(user) },
                        compact = compact,
                    )
                }
            }
        }
    }
}

/**
 * One of the server's public users, in a ringed circle above their name.
 *
 * [selected] lights the ring up solid primary and shows the name in full white; unselected users
 * get a faint neutral ring and a muted name.
 *
 * [avatarUrl] carries the profile picture the server advertises; it is `null` for users who have
 * none (`primaryImageTag == null`), and those keep the initial-letter circle instead of showing an
 * empty hole.
 *
 * The **whole column** is the selectable, not the circle inside it (accessibility audit 2026-08-05,
 * F6). Two things were wrong with the circle owning the click: selection was conveyed by ring colour
 * alone, with nothing in the semantics saying which profile was in force; and the name — the only
 * place the user's actual name is written — sat outside the clickable's merged node, so a user
 * without a profile picture announced as the one letter drawn in their fallback circle. "C", not
 * "claude". `Role.RadioButton` inside the row's `selectableGroup()` gives the name, the state and
 * the position in the set, all on one stop.
 */
@Composable
private fun PublicUserAvatar(
    user: PublicUserInfo,
    avatarUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean,
) {
    val ringColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.White.copy(alpha = AVATAR_RING_UNSELECTED_ALPHA)
        }

    Column(
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Surface(
            modifier = Modifier.size(if (compact) AvatarSizeCompact else AvatarSize),
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .border(width = AvatarRingWidth, color = ringColor, shape = CircleShape)
                        .padding(AvatarRingWidth + AvatarRingGap)
                        .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = user.name.take(1).uppercase(),
                            // Muted: the initial is a drawing of the name, and the name itself is
                            // right below inside the same merged node. Spoken, it was the whole bug.
                            modifier = Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                } else {
                    JellyfinAsyncImage(
                        url = avatarUrl,
                        // Decorative now: the name Text below is part of this node's merged
                        // description, so describing the picture too says the name twice.
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        placeholderIcon = null,
                    )
                }
            }
        }
        Text(
            text = user.name,
            style = AvatarNameStyle,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun QuickConnectDialog(
    state: QuickConnectUiState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier.border(
                width = GlassDefaults.HairlineWidth,
                color = GlassDefaults.PanelHairline,
                shape = MaterialTheme.shapes.extraLarge,
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.login_quick_connect_cancel))
            }
        },
        title = {
            Text(text = stringResource(R.string.login_quick_connect_title), style = QuickConnectTitleStyle)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.login_quick_connect_instructions),
                    style = QuickConnectInstructionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuickConnectCodeRow(code = state.code)
                if (state.isWaiting) {
                    Row(
                        // Polite live region: the dialog opens on the code, and this line appears
                        // (and later disappears, when the code is approved and the token exchange
                        // starts) with nothing else on screen changing (audit 2026-08-05, F4).
                        modifier =
                            Modifier.semantics(mergeDescendants = true) {
                                liveRegion = LiveRegionMode.Polite
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.14f),
                        )
                        Text(
                            text = stringResource(R.string.login_quick_connect_waiting),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}

/**
 * The Quick Connect code, one digit box per character — [code] is a plain string rather than a
 * fixed-length type, so this sizes itself to whatever length the server hands back instead of
 * assuming six.
 *
 * The boxes narrow below [QuickConnectDigitWidth] when the dialog can't fit them all — a phone-width
 * `AlertDialog` is narrower than six full boxes — because a code the user has to scroll to read
 * defeats the point of showing it.
 *
 * To a screen reader it is **one** node, not one per box: six separate stops each holding a bare
 * glyph is a code you have to assemble yourself from six swipes, with nothing saying what the digits
 * are for (accessibility audit 2026-08-05, F3). The single description names it and spells the code
 * out character by character — [spacedOutCode] — because a TTS engine reads "482913" as "four
 * hundred and eighty-two thousand nine hundred and thirteen", which is not a code anybody can type.
 */
@Composable
private fun QuickConnectCodeRow(code: String) {
    val description = stringResource(R.string.login_quick_connect_code_description, spacedOutCode(code))
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val count = code.length.coerceAtLeast(1)
        val fitWidth = (maxWidth - QuickConnectDigitGap * (count - 1)) / count
        val digitWidth = minOf(QuickConnectDigitWidth, fitWidth)
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(QuickConnectDigitGap),
        ) {
            code.forEach { digit -> QuickConnectDigitBox(digit = digit, width = digitWidth) }
        }
    }
}

/**
 * `"482913"` → `"4 8 2 9 1 3"`: the code as characters to be read one at a time.
 *
 * Spaces rather than any other separator because that is what every TTS engine already treats as a
 * pause; a comma or hyphen would be spoken as itself in some locales. Whitespace inside the code is
 * dropped rather than doubled — the server hands back a bare alphanumeric code, but a code with a
 * stray space in it would otherwise be announced with a hole in the middle of it.
 */
internal fun spacedOutCode(code: String): String = code.filterNot { it.isWhitespace() }.toList().joinToString(" ")

@Composable
private fun QuickConnectDigitBox(
    digit: Char,
    width: Dp,
) {
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    Box(
        modifier =
            Modifier
                // Width is fixed — the row measures it so N boxes fit the viewport — but the height
                // is a *floor*: the digit inside is the largest type on the screen, and at
                // accessibility font scales a fixed box clipped the very code the user is meant to
                // read out. The boxes are laid out in a `Row`, so one growing takes the rest with
                // it and they stay a set (`GlassBottomNav` records the same min-not-fixed rule).
                .width(width)
                .heightIn(min = QuickConnectDigitHeight)
                .background(color = QuickConnectDigitFill, shape = shape)
                .border(width = GlassDefaults.HairlineWidth, color = GlassDefaults.Hairline, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = digit.toString(), style = QuickConnectDigitStyle, color = Color.White)
    }
}

private val PreviewPublicUsers =
    listOf(
        PublicUserInfo(id = UUID.randomUUID(), name = "claude", primaryImageTag = null),
        PublicUserInfo(id = UUID.randomUUID(), name = "casey", primaryImageTag = null),
    )

private val PreviewLoginState =
    LoginUiState(
        serverName = "Living Room",
        serverVersion = "10.11.0",
        isLoadingContext = false,
        publicUsers = PreviewPublicUsers,
        quickConnectEnabled = true,
        username = "claude",
    )

@Preview(name = "Login — portrait", showBackground = true, backgroundColor = 0xFF101010, widthDp = 400, heightDp = 900)
@Composable
private fun LoginPortraitPreview() {
    JellyfinTheme {
        LoginContent(
            state = PreviewLoginState,
            onUsernameChange = {},
            onPasswordChange = {},
            onPublicUserSelected = {},
            onSignIn = {},
            onStartQuickConnect = {},
            onCancelQuickConnect = {},
            onChangeServer = {},
        )
    }
}

@Preview(name = "Login — two-pane", showBackground = true, backgroundColor = 0xFF101010, widthDp = 1000, heightDp = 700)
@Composable
private fun LoginTwoPanePreview() {
    JellyfinTheme {
        LoginContent(
            state = PreviewLoginState,
            onUsernameChange = {},
            onPasswordChange = {},
            onPublicUserSelected = {},
            onSignIn = {},
            onStartQuickConnect = {},
            onCancelQuickConnect = {},
            onChangeServer = {},
        )
    }
}

// A typical compact-width phone window (docs/PLAN.md target: ~360x800dp, minus system bars).
@Preview(name = "Login — phone", showBackground = true, backgroundColor = 0xFF101010, widthDp = 360, heightDp = 740)
@Composable
private fun LoginPhonePreview() {
    JellyfinTheme {
        LoginContent(
            state = PreviewLoginState,
            onUsernameChange = {},
            onPasswordChange = {},
            onPublicUserSelected = {},
            onSignIn = {},
            onStartQuickConnect = {},
            onCancelQuickConnect = {},
            onChangeServer = {},
        )
    }
}

@Preview(
    name = "Quick Connect dialog",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 400,
    heightDp = 500,
)
@Composable
private fun QuickConnectDialogPreview() {
    JellyfinTheme {
        QuickConnectDialog(state = QuickConnectUiState(code = "482913"), onDismiss = {})
    }
}
