package dev.jellyboost.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.network.model.PublicUserInfo
import dev.jellyboost.core.ui.component.FieldContent
import dev.jellyboost.core.ui.component.FieldLabel
import dev.jellyboost.core.ui.component.FieldState
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.JellyboostAlertDialog
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.component.JellyfinTextField
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.pageInk
import java.util.UUID
import dev.jellyboost.core.ui.R as CoreUiR

private val AvatarSize = 88.dp

private val AvatarSizeCompact = 64.dp

private val AvatarRingWidth = 2.dp

private val AvatarRingGap = 3.dp

private val AvatarRowSpacing = 32.dp

private val AvatarRowSpacingCompact = 20.dp

private const val AVATAR_RING_UNSELECTED_ALPHA = 0.10f

private val WhosWatchingStyle =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.12.em,
    )

private val ServerNameStyle =
    TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = (-0.02).em,
    )

private val ServerNameStyleCompact = ServerNameStyle.copy(fontSize = 26.sp)

private val AvatarNameStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)

private val OrDividerTextStyle =
    TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.1.em,
    )

private val ChangeServerLinkStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)

private val QuickConnectTitleStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.W600)

private val QuickConnectInstructionStyle = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)

private val QuickConnectDigitWidth = 46.dp
private val QuickConnectDigitHeight = 58.dp
private val QuickConnectDigitGap = 8.dp
private val QuickConnectDigitStyle =
    TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.W600,
        // Tabular figures: varying digit widths would make the boxes wobble character to character.
        fontFeatureSettings = "tnum",
    )
private val QuickConnectDigitFill: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.05f)

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
            LoginHeader(
                state = state,
                compact = compact,
                onPublicUserSelected = onPublicUserSelected,
            )
        },
        modifier = modifier,
    ) {
        AuthPanel {
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

    state.quickConnect?.let { quickConnect ->
        QuickConnectDialog(state = quickConnect, onDismiss = onCancelQuickConnect)
    }
}

@Composable
private fun ColumnScope.LoginHeader(
    state: LoginUiState,
    compact: Boolean,
    onPublicUserSelected: (PublicUserInfo) -> Unit,
) {
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
        LoginContextLoading()
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
            // Selection is derived, not stored: the highlighted profile is the one the field names.
            selectedName = state.username,
            onUserSelected = onPublicUserSelected,
            compact = compact,
        )
    }
}

/** Bar and caption are one polite live region: the screen arrives already loading. */
@Composable
private fun LoginContextLoading() {
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

    // Both fields carry the failure: a rejected sign-in does not say which of the two was wrong.
    // Both stay *enabled* while the exchange runs — disabling the field a TalkBack user stands on
    // destroys its node and drops focus to the top. `LoginViewModel` ignores edits while `isSigningIn`,
    // and clears the error when an exchange starts, so error and in-flight never meet.
    val fieldState =
        when {
            state.error != null -> FieldState.Error(authErrorText(state.error))
            state.isSigningIn -> FieldState.InFlight
            else -> FieldState.Editable
        }

    JellyfinTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = FieldLabel.eyebrow(stringResource(R.string.login_username_label)),
        state = fieldState,
        content = FieldContent.Plain(autofill = ContentType.Username),
        // Autocorrect off: an IME fixing an account name produces a failure with no visible cause.
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
    )

    LoginPasswordField(
        value = state.password,
        onValueChange = onPasswordChange,
        fieldState = fieldState,
        revealed = passwordVisible,
        onToggleReveal = { passwordVisible = !passwordVisible },
        onDone = {
            keyboardController?.hide()
            onSignIn()
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

    LoginSecondaryActions(
        quickConnectEnabled = state.quickConnectEnabled,
        quickConnectAllowed = !state.isSigningIn,
        onStartQuickConnect = onStartQuickConnect,
        onChangeServer = onChangeServer,
    )
}

@Composable
private fun LoginSecondaryActions(
    quickConnectEnabled: Boolean,
    quickConnectAllowed: Boolean,
    onStartQuickConnect: () -> Unit,
    onChangeServer: () -> Unit,
) {
    if (quickConnectEnabled) {
        OrDivider()
        GhostPillButton(
            text = stringResource(R.string.login_quick_connect),
            onClick = onStartQuickConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = quickConnectAllowed,
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

/** [revealed] draws the characters; the node is marked as holding a secret either way. */
@Composable
private fun LoginPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    fieldState: FieldState,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onDone: () -> Unit,
) {
    JellyfinTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = FieldLabel.eyebrow(stringResource(R.string.login_password_label)),
        state = fieldState,
        content = FieldContent.Password(revealed = revealed),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onToggleReveal) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription =
                        stringResource(
                            if (revealed) R.string.login_hide_password else R.string.login_show_password,
                        ),
                )
            }
        },
    )
}

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
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                // `selectableGroup()` is what makes N circles one radio set, so TalkBack can say "2 of 3".
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
 * The whole column is the selectable, not the circle: otherwise selection is conveyed by ring
 * colour alone and the name sits outside the merged node, so a user with no picture is announced
 * as the single letter in their fallback circle.
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
            pageInk(darkAlpha = AVATAR_RING_UNSELECTED_ALPHA)
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
                AvatarFace(name = user.name, avatarUrl = avatarUrl)
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

/** Both are decorative: the name is written below inside the same merged node. */
@Composable
private fun AvatarFace(
    name: String,
    avatarUrl: String?,
) {
    if (avatarUrl == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                modifier = Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    } else {
        JellyfinAsyncImage(
            url = avatarUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            placeholderIcon = null,
        )
    }
}

@Composable
private fun QuickConnectDialog(
    state: QuickConnectUiState,
    onDismiss: () -> Unit,
) {
    JellyboostAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CoreUiR.string.action_cancel))
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
                        // Polite live region: this line appears and disappears with nothing else on screen changing.
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
                            trackColor = pageInk(darkAlpha = 0.14f),
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
 * Sized to whatever code length the server hands back, narrowing the boxes when a phone-width
 * dialog cannot fit them. One node, not one per box, and spelled out — a TTS engine reads "482913"
 * as "four hundred and eighty-two thousand nine hundred and thirteen".
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
 * Spaces because every TTS engine treats them as a pause; a comma or hyphen is spoken as itself in
 * some locales. Whitespace in the code is dropped rather than doubled.
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
                // Height is a *floor*, never fixed: at large font scales a fixed box clipped the code itself.
                .width(width)
                .heightIn(min = QuickConnectDigitHeight)
                .background(color = QuickConnectDigitFill, shape = shape)
                .border(width = GlassDefaults.HairlineWidth, color = GlassDefaults.Hairline, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit.toString(),
            style = QuickConnectDigitStyle,
            color = MaterialTheme.colorScheme.onBackground,
        )
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
