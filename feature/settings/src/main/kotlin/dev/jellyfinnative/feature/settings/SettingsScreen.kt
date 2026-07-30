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
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.formatBytes
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme
import dev.jellyfinnative.data.downloads.model.StorageLocations
import dev.jellyfinnative.data.downloads.model.StorageUsage
import dev.jellyfinnative.data.downloads.model.StorageVolumeOption

/**
 * The Settings screen (docs/PLAN.md, "Screens" → Settings): preferences, account and sign-out.
 *
 * Reached from the home top bar's overflow menu rather than from a user avatar — there is no avatar
 * asset pipeline in this app (DECISIONS.md 2026-07-29, "Settings is opened from the home overflow
 * menu"). It is a pushed destination, not a bottom-nav tab, so it owns a `TopAppBar` with a back
 * action the way `LibraryGridScreen` and `ItemDetailScreen` do.
 *
 * The Downloads section's storage **location picker** chooses between the app-specific directories
 * the platform reports — internal storage and, when one is in, the SD card. Picking an arbitrary
 * folder still waits on SAF, which stays deferred behind the `DownloadStorage` seam (DECISIONS.md
 * 2026-07-29, "the storage location picker ships now, backed by secondary volumes").
 *
 * @param viewModel passed in rather than resolved here so `:app` owns the `hiltViewModel()` call.
 * @param onBack pops this destination off the back stack.
 * @param onHome leaves the whole pushed chain at once and lands on the Home tab; see
 *   `AppScaffold.navigateHome`.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onHome: () -> Unit,
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
                onDownloadQuality = viewModel::setDownloadQuality,
                onStorageLocation = viewModel::setStorageLocation,
                onForceOffline = viewModel::setForceOffline,
                onSignOut = viewModel::signOut,
            ),
        onBack = onBack,
        onHome = onHome,
        modifier = modifier,
    )
}

/** Every action the screen can raise, bundled so the section composables stay small. */
data class SettingsActions(
    val onIntroSkipMode: (SegmentSkipMode) -> Unit,
    val onOutroSkipMode: (SegmentSkipMode) -> Unit,
    val onPipOnLeave: (Boolean) -> Unit,
    val onWifiOnly: (Boolean) -> Unit,
    val onDownloadQuality: (DownloadQuality) -> Unit,
    /** Volume id, and whether the user agreed to lose the downloads already on the device. */
    val onStorageLocation: (String, Boolean) -> Unit,
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
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingSignOut by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    // Same two-affordance navigation slot the other pushed screens carry, so the
                    // way out of a pushed destination is in one place wherever the user is.
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                            )
                        }
                        IconButton(onClick = onHome) {
                            Icon(
                                imageVector = Icons.Filled.Home,
                                contentDescription = stringResource(R.string.settings_home),
                            )
                        }
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
        DownloadQualityGroup(selected = state.downloadQuality, onSelect = actions.onDownloadQuality)
        StorageRow(usage = state.storage)
        StorageLocationGroup(locations = state.storageLocations, onSelect = actions.onStorageLocation)
    }
}

/**
 * The storage-location picker (docs/PLAN.md, "Screens" → Settings).
 *
 * One row per **mounted** volume: internal storage always, the SD card when there is one. A card
 * that is not in the device is not a disabled row, it is no row — there is nothing to explain about
 * a choice that is not on offer, and the missing-selection warning above covers the one case where
 * its absence matters. The group hides itself entirely when there is only one place to put files,
 * which is what most devices look like.
 *
 * Switching while downloads exist deletes them (the plan's v1 policy — files are not moved yet), so
 * that switch goes through a confirmation. Switching with an empty device is immediate: there is
 * nothing to lose and nothing to warn about.
 */
@Composable
private fun StorageLocationGroup(
    locations: StorageLocations,
    onSelect: (String, Boolean) -> Unit,
) {
    if (locations.volumes.size < 2) return
    var pendingVolumeId by remember { mutableStateOf<String?>(null) }

    if (locations.selectedVolumeMissing) {
        Text(
            text = stringResource(R.string.settings_storage_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier =
                Modifier.padding(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = Dimens.SpaceSmall,
                ),
        )
    }

    SettingsChoiceGroup(label = stringResource(R.string.settings_storage_picker)) {
        locations.volumes.forEach { volume ->
            SettingsChoiceRow(
                label = volume.label(),
                supportingText =
                    stringResource(R.string.settings_storage_volume_free, formatBytes(volume.availableBytes)),
                selected = volume.id == locations.activeVolumeId,
                onSelect = {
                    when {
                        // Tapping the row that is already in force normally does nothing. The
                        // exception is a stale choice: with the card out, this is how the user says
                        // "just use this one" — the files are already here, so nothing is deleted.
                        volume.id == locations.activeVolumeId ->
                            if (locations.selectedVolumeMissing) onSelect(volume.id, false)

                        locations.downloadCount > 0 -> pendingVolumeId = volume.id
                        else -> onSelect(volume.id, false)
                    }
                },
            )
        }
    }

    pendingVolumeId?.let { volumeId ->
        SwitchStorageDialog(
            downloadCount = locations.downloadCount,
            onDismiss = { pendingVolumeId = null },
            onConfirm = {
                pendingVolumeId = null
                onSelect(volumeId, true)
            },
        )
    }
}

/** Confirms that switching location throws the downloads away, because nothing moves them yet. */
@Composable
private fun SwitchStorageDialog(
    downloadCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_storage_switch_title)) },
        text = {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.settings_storage_switch_message,
                        downloadCount,
                        downloadCount,
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_storage_switch_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
    )
}

/**
 * What to call a volume.
 *
 * The platform's own description first — it is localised, and it knows whether the thing is an SD
 * card, a USB drive or something a manufacturer named itself. Our two strings are the fallback for
 * the devices that will not say.
 */
@Composable
private fun StorageVolumeOption.label(): String =
    description ?: stringResource(
        if (isRemovable) R.string.settings_storage_sd_card else R.string.settings_storage_internal,
    )

/**
 * The download-quality picker (M9).
 *
 * Each option carries its bitrate in the label rather than in a supporting line, because what the
 * user is choosing between is four numbers and the numbers are the choice. The caveat under the
 * group is the one thing they cannot infer: a transcoded download shows an estimated size and does
 * not resume (see docs/features/download-quality.md).
 */
@Composable
private fun DownloadQualityGroup(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    SettingsChoiceGroup(label = stringResource(R.string.settings_quality)) {
        DownloadQuality.entries.forEach { quality ->
            SettingsChoiceRow(
                label = stringResource(quality.labelRes()),
                selected = quality == selected,
                onSelect = { onSelect(quality) },
            )
        }
    }
    Text(
        text = stringResource(R.string.settings_quality_supporting),
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

/**
 * How much room the downloads take and where they are — the *active* root, fallback included.
 *
 * Still informational: the picker underneath is what changes it. The path stays on screen because
 * it is the one thing a user can check against a file manager when a card is involved.
 */
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

private fun DownloadQuality.labelRes(): Int =
    when (this) {
        DownloadQuality.ORIGINAL -> R.string.settings_quality_original
        DownloadQuality.HIGH -> R.string.settings_quality_high
        DownloadQuality.MEDIUM -> R.string.settings_quality_medium
        DownloadQuality.LOW -> R.string.settings_quality_low
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
                    downloadQuality = DownloadQuality.MEDIUM,
                    forceOffline = false,
                    storage =
                        StorageUsage(
                            usedBytes = 12_300_000_000L,
                            availableBytes = 41_000_000_000L,
                            rootPath = "/storage/emulated/0/Android/data/dev.jellyfinnative.app/files/downloads",
                        ),
                    storageLocations =
                        StorageLocations(
                            volumes =
                                listOf(
                                    StorageVolumeOption(
                                        id = "primary",
                                        description = "Internal shared storage",
                                        isRemovable = false,
                                        path = "/storage/emulated/0/Android/data/dev.jellyfinnative.app/files",
                                        availableBytes = 41_000_000_000L,
                                    ),
                                    StorageVolumeOption(
                                        id = "1A2B-3C4D",
                                        description = "SD card",
                                        isRemovable = true,
                                        path = "/storage/1A2B-3C4D/Android/data/dev.jellyfinnative.app/files",
                                        availableBytes = 118_000_000_000L,
                                    ),
                                ),
                            activeVolumeId = "primary",
                            downloadCount = 3,
                        ),
                    account = AccountInfo(userName = "casey", serverName = "Living Room"),
                ),
            actions =
                SettingsActions(
                    onIntroSkipMode = {},
                    onOutroSkipMode = {},
                    onPipOnLeave = {},
                    onWifiOnly = {},
                    onDownloadQuality = {},
                    onStorageLocation = { _, _ -> },
                    onForceOffline = {},
                    onSignOut = {},
                ),
            onBack = {},
            onHome = {},
        )
    }
}
