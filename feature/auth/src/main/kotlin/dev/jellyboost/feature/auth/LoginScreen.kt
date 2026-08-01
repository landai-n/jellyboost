package dev.jellyboost.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.network.model.PublicUserInfo
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients

private val AvatarSize = 80.dp

/** Gradient ring around every public-user avatar — the brand cue that ties the two screens. */
private val AvatarRingWidth = 2.dp

/** Breathing room between the ring and the picture it frames, so the ring doesn't crop it. */
private val AvatarRingGap = 3.dp

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
    val keyboardController = LocalSoftwareKeyboardController.current
    var passwordVisible by remember { mutableStateOf(false) }

    AuthScreenScaffold(
        header = {
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
                    style = MaterialTheme.typography.headlineMedium,
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
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.login_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    onUserSelected = onPublicUserSelected,
                )
            }
        },
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isSigningIn,
            label = { Text(text = stringResource(R.string.login_username_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isSigningIn,
            label = { Text(text = stringResource(R.string.login_password_label)) },
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

        Button(
            onClick = {
                keyboardController?.hide()
                onSignIn()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSignIn,
        ) {
            if (state.isSigningIn) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(Dimens.SpaceSmall))
            }
            Text(text = stringResource(R.string.login_sign_in))
        }

        state.error?.let { error -> AuthErrorBlock(message = error) }

        if (state.quickConnectEnabled) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.login_or),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onStartQuickConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSigningIn,
            ) {
                Text(text = stringResource(R.string.login_quick_connect))
            }
        }

        TextButton(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.login_change_server))
        }

        Spacer(modifier = Modifier.height(Dimens.SpaceLarge))
    }

    state.quickConnect?.let { quickConnect ->
        QuickConnectDialog(state = quickConnect, onDismiss = onCancelQuickConnect)
    }
}

@Composable
private fun PublicUsersRow(
    users: List<PublicUserInfo>,
    avatarUrlFor: (PublicUserInfo) -> String?,
    onUserSelected: (PublicUserInfo) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Text(
            text = stringResource(R.string.login_public_users_title),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // The Box centres the row while it fits; once there are enough users to overflow, the
        // row takes the full width and scrolls instead.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
            ) {
                users.forEach { user ->
                    PublicUserAvatar(
                        user = user,
                        avatarUrl = avatarUrlFor(user),
                        onClick = { onUserSelected(user) },
                    )
                }
            }
        }
    }
}

/**
 * One of the server's public users, in a gradient-ringed circle above their name.
 *
 * [avatarUrl] carries the profile picture the server advertises; it is `null` for users who have
 * none (`primaryImageTag == null`), and those keep the initial-letter circle instead of showing an
 * empty hole.
 */
@Composable
private fun PublicUserAvatar(
    user: PublicUserInfo,
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(AvatarSize),
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .border(width = AvatarRingWidth, brush = JellyfinGradients.Accent, shape = CircleShape)
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
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                } else {
                    JellyfinAsyncImage(
                        url = avatarUrl,
                        contentDescription = user.name,
                        modifier = Modifier.fillMaxSize(),
                        placeholderIcon = null,
                    )
                }
            }
        }
        Text(
            text = user.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.login_quick_connect_cancel))
            }
        },
        title = { Text(text = stringResource(R.string.login_quick_connect_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.login_quick_connect_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = state.code,
                    style =
                        MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp,
                            brush = JellyfinGradients.Accent,
                        ),
                )
                if (state.isWaiting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
