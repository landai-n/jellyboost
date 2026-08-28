package dev.jellyboost.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.core.ui.component.ConfirmDialog
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.JellyboostAlertDialog
import dev.jellyboost.core.ui.component.ScreenHeader
import dev.jellyboost.core.ui.component.ScreenHeaderTitle
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage
import dev.jellyboost.data.downloads.model.StorageVolumeOption
import timber.log.Timber
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The storage **location picker** chooses between the app-specific directories the platform
 * reports; an arbitrary folder still waits on SAF behind the `DownloadStorage` seam.
 *
 * @param appVersion passed in because this module cannot see `:app`'s `BuildConfig`.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    appVersion: String,
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
                onThemeMode = viewModel::setThemeMode,
                onDynamicColor = viewModel::setDynamicColorEnabled,
                onSignOut = viewModel::signOut,
            ),
        onBack = onBack,
        onHome = onHome,
        onOpenLicence = onOpenLicence,
        onOpenThirdPartyLicences = onOpenThirdPartyLicences,
        appVersion = appVersion,
        modifier = modifier,
    )
}

data class SettingsActions(
    val onIntroSkipMode: (SegmentSkipMode) -> Unit,
    val onOutroSkipMode: (SegmentSkipMode) -> Unit,
    val onPipOnLeave: (Boolean) -> Unit,
    val onWifiOnly: (Boolean) -> Unit,
    val onDownloadQuality: (DownloadQuality) -> Unit,
    val onStorageLocation: (String, Boolean) -> Unit,
    val onForceOffline: (Boolean) -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onDynamicColor: (Boolean) -> Unit,
    /** `true` also removes every downloaded file before the session ends. */
    val onSignOut: (Boolean) -> Unit,
)

/**
 * The width cap matters on a tablet: unconstrained, the label sits at one edge and its switch at
 * the other, unreadable and unreachable one-handed.
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    var confirmingSignOut by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        SettingsHeader(onBack = onBack, onHome = onHome)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = SettingsContentMaxWidth),
            ) {
                AppearanceSection(
                    themeMode = state.themeMode,
                    dynamicColorEnabled = state.dynamicColorEnabled,
                    onThemeMode = actions.onThemeMode,
                    onDynamicColor = actions.onDynamicColor,
                )
                HorizontalDivider()
                PlaybackSection(state = state, actions = actions)
                HorizontalDivider()
                DownloadsSection(state = state, actions = actions)
                HorizontalDivider()
                ConnectivitySection(state = state, actions = actions)
                HorizontalDivider()
                AccountSection(
                    account = state.account,
                    signingOut = state.signingOut,
                    // Once the sign-out is away there is nothing to confirm: it cannot be cancelled, and a
                    // second one would delete the downloads twice.
                    onSignOutClick = { if (!state.signingOut) confirmingSignOut = true },
                )
                HorizontalDivider()
                AboutSection(
                    appVersion = appVersion,
                    onOpenLicence = onOpenLicence,
                    onOpenThirdPartyLicences = onOpenThirdPartyLicences,
                )
            }
        }
    }

    if (confirmingSignOut && !state.signingOut) {
        SignOutDialog(
            onDismiss = { confirmingSignOut = false },
            onConfirm = { deleteDownloads ->
                confirmingSignOut = false
                actions.onSignOut(deleteDownloads)
            },
        )
    }
}

@Composable
private fun SettingsHeader(
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    ScreenHeader(onBack = onBack, onHome = onHome) {
        ScreenHeaderTitle(text = stringResource(R.string.settings_title))
    }
}

internal val SettingsContentMaxWidth: Dp = 640.dp

/**
 * Scalars rather than the whole `SettingsUiState`: this section redraws on a theme change, and a
 * state parameter would also redraw it on every storage tick.
 *
 * The dynamic-colour row is **absent**, not disabled, below API 31 — the platform has no wallpaper
 * palette there, so the switch would be a control with nothing behind it.
 */
@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    dynamicColorEnabled: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
        val groupLabel = stringResource(R.string.settings_theme)
        SettingsChoiceGroup(label = groupLabel) {
            ThemeMode.entries.forEach { mode ->
                SettingsChoiceRow(
                    groupLabel = groupLabel,
                    label = stringResource(mode.labelRes()),
                    selected = mode == themeMode,
                    onSelect = { onThemeMode(mode) },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SettingsSwitchRow(
                label = stringResource(R.string.settings_dynamic_color),
                supportingText = stringResource(R.string.settings_dynamic_color_supporting),
                checked = dynamicColorEnabled,
                onCheckedChange = onDynamicColor,
            )
        }
    }
}

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
                // Both skip groups draw the same three options; the group name is the only thing
                // telling one set apart from the other.
                groupLabel = label,
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
 * One row per *mounted* volume; a card that is not in the device is no row at all, and the group
 * hides itself when there is only one place to put files.
 *
 * Switching while downloads exist deletes them — files are not moved yet — so it is confirmed.
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
                Modifier
                    .padding(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        bottom = Dimens.SpaceSmall,
                    )
                    // Assertive: the chosen volume is *gone* and downloads are landing elsewhere. It also
                    // appears on arrival rather than in response to a tap, so nothing else would surface it.
                    .semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }

    val pickerLabel = stringResource(R.string.settings_storage_picker)
    // The affordance the row carries only for someone who can see it is the odd one out: tapping
    // the already-selected row stores the fallback as the choice and clears the warning.
    val recoveryHint = stringResource(R.string.settings_storage_use_this_hint)

    SettingsChoiceGroup(label = pickerLabel) {
        locations.volumes.forEach { volume ->
            val isActive = volume.id == locations.activeVolumeId
            SettingsChoiceRow(
                groupLabel = pickerLabel,
                label = volume.label(),
                supportingText =
                    stringResource(R.string.settings_storage_volume_free, formatBytes(volume.availableBytes)),
                actionHint = recoveryHint.takeIf { isActive && locations.selectedVolumeMissing },
                selected = isActive,
                onSelect = {
                    when {
                        // Tapping the row already in force normally does nothing. With the card out this is how
                        // the user says "just use this one" — the files are already here, so nothing is deleted.
                        isActive -> if (locations.selectedVolumeMissing) onSelect(volume.id, false)

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

/** Switching location throws the downloads away, because nothing moves them yet. */
@Composable
private fun SwitchStorageDialog(
    downloadCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.settings_storage_switch_title),
        text =
            pluralStringResource(
                R.plurals.settings_storage_switch_message,
                downloadCount,
                downloadCount,
            ),
        confirmLabel = stringResource(R.string.settings_storage_switch_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The platform's own description first: it is localised and knows whether the thing is an SD card,
 * a USB drive or something a manufacturer named itself.
 */
@Composable
private fun StorageVolumeOption.label(): String =
    description ?: stringResource(
        if (isRemovable) R.string.settings_storage_sd_card else R.string.settings_storage_internal,
    )

/**
 * The caveat under the group is the one thing the labels cannot convey: a transcoded download shows
 * an estimated size and does not resume (docs/features/download-quality.md).
 */
@Composable
private fun DownloadQualityGroup(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    val groupLabel = stringResource(R.string.settings_quality)
    SettingsChoiceGroup(label = groupLabel) {
        DownloadQuality.entries.forEach { quality ->
            SettingsChoiceRow(
                groupLabel = groupLabel,
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

/** The path stays on screen: it is what a user checks against a file manager when a card is involved. */
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

/**
 * The button holds a spinner and stops taking taps while a sign-out is in flight: telling an
 * unreachable server is capped at seconds (`SessionRepository.SERVER_GOODBYE_TIMEOUT`), and a button
 * that answers nothing for that long reads as broken.
 */
@Composable
private fun AccountSection(
    account: AccountInfo?,
    signingOut: Boolean,
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
        GhostPillButton(
            text = stringResource(R.string.settings_sign_out),
            onClick = onSignOutClick,
            enabled = !signingOut,
            loading = signingOut,
            modifier =
                Modifier
                    .padding(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.SpaceMedium,
                        bottom = Dimens.SpaceMedium,
                    ),
        )
    }
}

/**
 * Last in the list, so it carries the screen's bottom breathing room rather than the sign-out button.
 *
 * The three rows below the version are what makes the binary distributable: GPL-3.0 §4 requires the
 * licence to be conveyed with it, §6 requires the corresponding source to be offered, and the bundled
 * Apache-2.0 artifacts each require their own notice.
 */
@Composable
private fun AboutSection(
    appVersion: String,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_about)) {
        SettingsInfoRow(
            label = stringResource(R.string.settings_version),
            value = appVersion,
        )
        SourceCodeRow()
        SettingsActionRow(
            label = stringResource(R.string.settings_licence),
            supportingText = stringResource(R.string.settings_licence_name),
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            onClick = onOpenLicence,
        )
        SettingsActionRow(
            label = stringResource(R.string.settings_third_party),
            supportingText = stringResource(R.string.settings_third_party_supporting),
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            onClick = onOpenThirdPartyLicences,
            modifier = Modifier.padding(bottom = Dimens.SpaceExtraLarge),
        )
    }
}

/** GPL-3.0 §6's offer of the corresponding source; the URL is the offer, so it stays on screen. */
@Composable
private fun SourceCodeRow() {
    val context = LocalContext.current
    val failureMessage = stringResource(R.string.settings_open_link_failed)

    SettingsActionRow(
        label = stringResource(R.string.settings_source_code),
        supportingText = SOURCE_CODE_URL,
        icon = Icons.AutoMirrored.Filled.OpenInNew,
        onClick = { openSourceCode(context, failureMessage) },
    )
}

private fun openSourceCode(
    context: Context,
    failureMessage: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri()))
    } catch (error: ActivityNotFoundException) {
        // Permanent, not transient: a device with no browser does not grow one on a retry, so the
        // user is told once rather than left with a row that silently does nothing.
        Timber.w(error, "No activity on this device can open a web link")
        Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
    }
}

private const val SOURCE_CODE_URL = "https://github.com/landai-n/jellyboost"

/**
 * The delete-downloads box is unchecked by default: a user signing out to switch accounts on a
 * shared tablet should not lose a weekend's downloads to a remembered answer.
 */
@Composable
private fun SignOutDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var deleteDownloads by remember { mutableStateOf(false) }

    JellyboostAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteDownloads) }) {
                Text(text = stringResource(R.string.settings_sign_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CoreUiR.string.action_cancel))
            }
        },
        title = { Text(text = stringResource(R.string.settings_sign_out_dialog_title)) },
        // Not a [ConfirmDialog]: the body carries a checkbox as well as a sentence, and that choice is
        // part of what the confirm button does.
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
                Text(text = stringResource(R.string.settings_sign_out_dialog_message))
                DeleteDownloadsCheckbox(
                    checked = deleteDownloads,
                    onCheckedChange = { deleteDownloads = it },
                )
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

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

private fun SegmentSkipMode.labelRes(): Int =
    when (this) {
        SegmentSkipMode.OFF -> R.string.settings_skip_mode_off
        SegmentSkipMode.SHOW_BUTTON -> R.string.settings_skip_mode_show_button
        SegmentSkipMode.AUTO_SKIP -> R.string.settings_skip_mode_auto
    }

private val PreviewState =
    SettingsUiState(
        introSkipMode = SegmentSkipMode.AUTO_SKIP,
        outroSkipMode = SegmentSkipMode.SHOW_BUTTON,
        pipOnLeave = true,
        downloadOverWifiOnly = true,
        downloadQuality = DownloadQuality.MEDIUM,
        forceOffline = false,
        themeMode = ThemeMode.SYSTEM,
        dynamicColorEnabled = false,
        storage =
            StorageUsage(
                usedBytes = 12_300_000_000L,
                availableBytes = 41_000_000_000L,
                rootPath = "/storage/emulated/0/Android/data/dev.jellyboost.app/files/downloads",
            ),
        storageLocations =
            StorageLocations(
                volumes =
                    listOf(
                        StorageVolumeOption(
                            id = "primary",
                            description = "Internal shared storage",
                            isRemovable = false,
                            path = "/storage/emulated/0/Android/data/dev.jellyboost.app/files",
                            availableBytes = 41_000_000_000L,
                        ),
                        StorageVolumeOption(
                            id = "1A2B-3C4D",
                            description = "SD card",
                            isRemovable = true,
                            path = "/storage/1A2B-3C4D/Android/data/dev.jellyboost.app/files",
                            availableBytes = 118_000_000_000L,
                        ),
                    ),
                activeVolumeId = "primary",
                downloadCount = 3,
            ),
        account = AccountInfo(userName = "casey", serverName = "Living Room"),
    )

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF101010, heightDp = 900)
@Composable
private fun SettingsPreview() {
    JellyfinTheme {
        SettingsContent(
            state = PreviewState,
            actions =
                SettingsActions(
                    onIntroSkipMode = {},
                    onOutroSkipMode = {},
                    onPipOnLeave = {},
                    onWifiOnly = {},
                    onDownloadQuality = {},
                    onStorageLocation = { _, _ -> },
                    onForceOffline = {},
                    onThemeMode = {},
                    onDynamicColor = {},
                    onSignOut = {},
                ),
            onBack = {},
            onHome = {},
            onOpenLicence = {},
            onOpenThirdPartyLicences = {},
            appVersion = "0.1.0-debug",
        )
    }
}
