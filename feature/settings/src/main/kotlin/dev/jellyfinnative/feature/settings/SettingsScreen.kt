package dev.jellyfinnative.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme
import dev.jellyfinnative.data.downloads.model.StorageUsage

/**
 * The Settings screen (docs/PLAN.md, "Screens" → Settings): preferences, account and sign-out.
 *
 * Reached from the home top bar's overflow menu rather than from a user avatar — there is no avatar
 * asset pipeline in this app (DECISIONS.md 2026-07-29, "Settings is opened from the home overflow
 * menu"). It is a pushed destination, not a bottom-nav tab, so it owns a `TopAppBar` with a back
 * action the way `LibraryGridScreen` and `ItemDetailScreen` do.
 *
 * The storage **location picker** the plan lists is deliberately absent: it ships with SAF support,
 * which was deferred behind the `DownloadStorage` seam at M7 (DECISIONS.md 2026-07-29, "the storage
 * location picker does not ship with the settings screen"). The Downloads section reports where
 * files live and how much room is left, and nothing more.
 *
 * @param viewModel passed in rather than resolved here so `:app` owns the `hiltViewModel()` call.
 * @param onBack pops this destination off the back stack.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        actions =
            SettingsActions(
                onIntroSkipMode = viewModel::setIntroSkipMode,
                onOutroSkipMode = viewModel::setOutroSkipMode,
                onPipOnLeave = viewModel::setPipOnLeave,
                onWifiOnly = viewModel::setDownloadOverWifiOnly,
                onForceOffline = viewModel::setForceOffline,
                onSignOut = viewModel::signOut,
            ),
        onBack = onBack,
        modifier = modifier,
    )
}

/** Every action the screen can raise, bundled so the section composables stay small. */
data class SettingsActions(
    val onIntroSkipMode: (SegmentSkipMode) -> Unit,
    val onOutroSkipMode: (SegmentSkipMode) -> Unit,
    val onPipOnLeave: (Boolean) -> Unit,
    val onWifiOnly: (Boolean) -> Unit,
    val onForceOffline: (Boolean) -> Unit,
    /** `true` also removes every downloaded file before the session ends. */
    val onSignOut: (Boolean) -> Unit,
)

/**
 * Stateless rendering — a pure function of [state], so it previews without a ViewModel.
 *
 * The width cap matters on the test tablet: an unconstrained settings list on a 2560 px-wide screen
 * puts the label at one edge and its switch at the other, which is unreadable and unreachable
 * one-handed. Same reasoning (and same shape) as `:feature:auth`'s `AuthContentMaxWidth`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingSignOut by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = SettingsContentMaxWidth),
            ) {
                PlaybackSection(state = state, actions = actions)
                HorizontalDivider()
                DownloadsSection(state = state, actions = actions)
                HorizontalDivider()
                ConnectivitySection(state = state, actions = actions)
                HorizontalDivider()
                AccountSection(account = state.account, onSignOutClick = { confirmingSignOut = true })
            }
        }
    }

    if (confirmingSignOut) {
        SignOutDialog(
            onDismiss = { confirmingSignOut = false },
            onConfirm = { deleteDownloads ->
                confirmingSignOut = false
                actions.onSignOut(deleteDownloads)
            },
        )
    }
}

/** How wide the list is allowed to get; wider than a login form, narrow enough to stay one column. */
internal val SettingsContentMaxWidth: Dp = 640.dp

@Composable
private fun PlaybackSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection(title = stringResource(R.string.settings_section_playback)) {
        SkipModeGroup(
            label = stringResource(R.string.settings_skip_intro),
            selected = state.introSkipMode,
            onSelect = actions.onIntroSkipMode,
        )
        SkipModeGroup(
            label = stringResource(R.string.settings_skip_outro),
            selected = state.outroSkipMode,
            onSelect = actions.onOutroSkipMode,
        )
        SettingsSwitchRow(
            label = stringResource(R.string.settings_pip),
            supportingText = stringResource(R.string.settings_pip_supporting),
            checked = state.pipOnLeave,
            onCheckedChange = actions.onPipOnLeave,
        )
    }
}

@Composable
private fun SkipModeGroup(
    label: String,
    selected: SegmentSkipMode,
    onSelect: (SegmentSkipMode) -> Unit,
) {
    SettingsChoiceGroup(label = label) {
        SegmentSkipMode.entries.forEach { mode ->
            SettingsChoiceRow(
                label = stringResource(mode.labelRes()),
                selected = mode == selected,
                onSelect = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun DownloadsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection(title = stringResource(R.string.settings_section_downloads)) {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_wifi_only),
            supportingText = stringResource(R.string.settings_wifi_only_supporting),
            checked = state.downloadOverWifiOnly,
            onCheckedChange = actions.onWifiOnly,
        )
        StorageRow(usage = state.storage)
    }
}

/** Informational only — changing where downloads live waits on SAF support (see the file KDoc). */
@Composable
private fun StorageRow(usage: StorageUsage) {
    SettingsInfoRow(
        label = stringResource(R.string.settings_storage_label),
        value =
            stringResource(
                R.string.settings_storage_summary,
                formatBytes(usage.usedBytes),
                formatBytes(usage.availableBytes),
            ),
    )
    usage.rootPath?.let { path ->
        Text(
            text = stringResource(R.string.settings_storage_location, path),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = Dimens.SpaceSmall,
                ),
        )
    }
}

@Composable
private fun ConnectivitySection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection(title = stringResource(R.string.settings_section_connectivity)) {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_offline_mode),
            supportingText = stringResource(R.string.settings_offline_mode_supporting),
            checked = state.forceOffline,
            onCheckedChange = actions.onForceOffline,
        )
    }
}

@Composable
private fun AccountSection(
    account: AccountInfo?,
    onSignOutClick: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_account)) {
        SettingsInfoRow(
            label = stringResource(R.string.settings_account_user),
            value = account?.userName ?: stringResource(R.string.settings_account_unknown),
        )
        if (account != null) {
            SettingsInfoRow(
                label = stringResource(R.string.settings_account_server),
                value = account.serverName,
            )
        }
        OutlinedButton(
            onClick = onSignOutClick,
            modifier =
                Modifier
                    .padding(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.SpaceMedium,
                        bottom = Dimens.SpaceExtraLarge,
                    ),
        ) {
            Text(text = stringResource(R.string.settings_sign_out))
        }
    }
}

/**
 * Confirms the sign-out and offers to take the downloads with it.
 *
 * Unchecked by default, and deliberately so: files are the expensive thing to get back, and a user
 * signing out to switch accounts on a shared tablet should not lose a series they downloaded over
 * a weekend because a checkbox remembered its last answer.
 */
@Composable
private fun SignOutDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var deleteDownloads by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_sign_out_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
                Text(text = stringResource(R.string.settings_sign_out_dialog_message))
                DeleteDownloadsCheckbox(
                    checked = deleteDownloads,
                    onCheckedChange = { deleteDownloads = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteDownloads) }) {
                Text(text = stringResource(R.string.settings_sign_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun DeleteDownloadsCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = stringResource(R.string.settings_sign_out_delete_downloads),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun SegmentSkipMode.labelRes(): Int =
    when (this) {
        SegmentSkipMode.OFF -> R.string.settings_skip_mode_off
        SegmentSkipMode.SHOW_BUTTON -> R.string.settings_skip_mode_show_button
        SegmentSkipMode.AUTO_SKIP -> R.string.settings_skip_mode_auto
    }

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF101010, heightDp = 900)
@Composable
private fun SettingsPreview() {
    JellyfinTheme {
        SettingsContent(
            state =
                SettingsUiState(
                    introSkipMode = SegmentSkipMode.AUTO_SKIP,
                    outroSkipMode = SegmentSkipMode.SHOW_BUTTON,
                    pipOnLeave = true,
                    downloadOverWifiOnly = true,
                    forceOffline = false,
                    storage =
                        StorageUsage(
                            usedBytes = 12_300_000_000L,
                            availableBytes = 41_000_000_000L,
                            rootPath = "/storage/emulated/0/Android/data/dev.jellyfinnative.app/files",
                        ),
                    account = AccountInfo(userName = "casey", serverName = "Living Room"),
                ),
            actions =
                SettingsActions(
                    onIntroSkipMode = {},
                    onOutroSkipMode = {},
                    onPipOnLeave = {},
                    onWifiOnly = {},
                    onForceOffline = {},
                    onSignOut = {},
                ),
            onBack = {},
        )
    }
}
