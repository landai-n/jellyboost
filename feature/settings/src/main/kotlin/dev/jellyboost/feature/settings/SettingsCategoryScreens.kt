package dev.jellyboost.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.SubtitleBackground
import dev.jellyboost.core.common.model.SubtitleTextSize
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.core.ui.component.ConfirmDialog
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.JellyboostAlertDialog
import dev.jellyboost.core.ui.component.ScreenHeader
import dev.jellyboost.core.ui.component.ScreenHeaderTitle
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage
import dev.jellyboost.data.downloads.model.StorageVolumeOption
import timber.log.Timber
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * A category as its own pushed screen — the compact path. The wide path draws the same body inside
 * a pane through [SettingsCategoryBody], which is what keeps the two shapes from drifting: there is
 * one copy of every page, and the shell around it is the only thing that differs.
 *
 * The header draws **Back only** (`design/foundations/navigation-chrome.html`).
 */
@Composable
fun SettingsCategoryScreen(
    viewModel: SettingsViewModel,
    category: SettingsCategory,
    onBack: () -> Unit,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        pane = SettingsPane.of(category),
        viewModel = viewModel,
        onBack = onBack,
        onOpenLicence = onOpenLicence,
        onOpenThirdPartyLicences = onOpenThirdPartyLicences,
        appVersion = appVersion,
        modifier = modifier,
    )
}

/** The Account page, reached from the hub's identity row. */
@Composable
fun SettingsAccountScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        pane = SettingsPane.ACCOUNT,
        viewModel = viewModel,
        onBack = onBack,
        onOpenLicence = {},
        onOpenThirdPartyLicences = {},
        appVersion = appVersion,
        modifier = modifier,
    )
}

@Composable
private fun SettingsPageScaffold(
    pane: SettingsPane,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsPageChrome(pane = pane, onBack = onBack, modifier = modifier) {
        SettingsCategoryBody(
            pane = pane,
            state = state,
            actions = rememberSettingsActions(viewModel),
            appVersion = appVersion,
            onOpenLicence = onOpenLicence,
            onOpenThirdPartyLicences = onOpenThirdPartyLicences,
        )
    }
}

/**
 * The pushed screen around a category body: a Back-only header, a scroll, and the 640dp cap.
 *
 * Separate from the body so the wide pane can draw the *same* body under its own chrome — one copy
 * of every page, and the shell is the only thing the two shapes disagree about.
 */
@Composable
internal fun SettingsPageChrome(
    pane: SettingsPane,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onBack = onBack) {
            ScreenHeaderTitle(text = stringResource(pane.titleRes()))
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = SettingsContentMaxWidth)
                        .padding(PageContentPadding),
            ) {
                content()
            }
        }
    }
}

/**
 * Every category's body, and the only copy of each. Sections are separated by a gap rather than a
 * rule: a hairline lives *inside* a panel, between two rows, and never between two panels.
 */
@Composable
internal fun SettingsCategoryBody(
    pane: SettingsPane,
    state: SettingsUiState,
    actions: SettingsActions,
    appVersion: String,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        when (pane) {
            SettingsPane.ACCOUNT ->
                AccountPage(
                    account = state.account,
                    signingOut = state.signingOut,
                    onSignOut = actions.onSignOut,
                )

            SettingsPane.PLAYBACK ->
                PlaybackPage(
                    introSkipMode = state.introSkipMode,
                    outroSkipMode = state.outroSkipMode,
                    pipOnLeave = state.pipOnLeave,
                    styledAssSubtitles = state.styledAssSubtitles,
                    subtitleTextSize = state.subtitleTextSize,
                    subtitleBackground = state.subtitleBackground,
                    onIntroSkipMode = actions.onIntroSkipMode,
                    onOutroSkipMode = actions.onOutroSkipMode,
                    onPipOnLeave = actions.onPipOnLeave,
                    onStyledAssSubtitles = actions.onStyledAssSubtitles,
                    onSubtitleTextSize = actions.onSubtitleTextSize,
                    onSubtitleBackground = actions.onSubtitleBackground,
                )

            SettingsPane.DOWNLOADS ->
                DownloadsPage(
                    downloadOverWifiOnly = state.downloadOverWifiOnly,
                    downloadQuality = state.downloadQuality,
                    storage = state.storage,
                    storageLocations = state.storageLocations,
                    onWifiOnly = actions.onWifiOnly,
                    onDownloadQuality = actions.onDownloadQuality,
                    onStorageLocation = actions.onStorageLocation,
                )

            SettingsPane.APPEARANCE ->
                AppearancePage(
                    themeMode = state.themeMode,
                    dynamicColorEnabled = state.dynamicColorEnabled,
                    onThemeMode = actions.onThemeMode,
                    onDynamicColor = actions.onDynamicColor,
                )

            SettingsPane.NETWORK ->
                NetworkPage(
                    forceOffline = state.forceOffline,
                    onForceOffline = actions.onForceOffline,
                )

            SettingsPane.ABOUT ->
                AboutPage(
                    appVersion = appVersion,
                    onOpenLicence = onOpenLicence,
                    onOpenThirdPartyLicences = onOpenThirdPartyLicences,
                )
        }
    }
}

private val PageContentPadding =
    PaddingValues(
        start = Dimens.ScreenPadding,
        end = Dimens.ScreenPadding,
        top = Dimens.SpaceSmall,
        bottom = Dimens.SpaceExtraLarge,
    )

// --- Playback -----------------------------------------------------------------------------------

/**
 * Scalars rather than the whole `SettingsUiState`: nothing here changes while a storage tick does,
 * and a state parameter would redraw the page anyway.
 *
 * Rows carry **no leading icon** — icons identify a category on the hub, and inside one they only
 * narrow the label column.
 */
@Composable
@Suppress("LongParameterList")
private fun PlaybackPage(
    introSkipMode: SegmentSkipMode,
    outroSkipMode: SegmentSkipMode,
    pipOnLeave: Boolean,
    styledAssSubtitles: Boolean,
    subtitleTextSize: SubtitleTextSize,
    subtitleBackground: SubtitleBackground,
    onIntroSkipMode: (SegmentSkipMode) -> Unit,
    onOutroSkipMode: (SegmentSkipMode) -> Unit,
    onPipOnLeave: (Boolean) -> Unit,
    onStyledAssSubtitles: (Boolean) -> Unit,
    onSubtitleTextSize: (SubtitleTextSize) -> Unit,
    onSubtitleBackground: (SubtitleBackground) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_eyebrow_during_playback)) {
        SkipModeGroup(
            label = stringResource(R.string.settings_skip_intro),
            selected = introSkipMode,
            onSelect = onIntroSkipMode,
        )
        SettingsRowSeparator()
        SkipModeGroup(
            label = stringResource(R.string.settings_skip_outro),
            selected = outroSkipMode,
            onSelect = onOutroSkipMode,
        )
        SettingsRowSeparator()
        SettingsSwitchRow(
            label = stringResource(R.string.settings_pip),
            supportingText = stringResource(R.string.settings_pip_supporting),
            checked = pipOnLeave,
            onCheckedChange = onPipOnLeave,
        )
    }
    SettingsSection(title = stringResource(R.string.settings_eyebrow_subtitles)) {
        SubtitleTextSizeGroup(selected = subtitleTextSize, onSelect = onSubtitleTextSize)
        SettingsRowSeparator()
        SubtitleBackgroundGroup(selected = subtitleBackground, onSelect = onSubtitleBackground)
        SettingsRowSeparator()
        SettingsSwitchRow(
            label = stringResource(R.string.settings_styled_ass),
            supportingText = stringResource(R.string.settings_styled_ass_supporting),
            checked = styledAssSubtitles,
            onCheckedChange = onStyledAssSubtitles,
        )
    }
}

/**
 * The two appearance groups sit *above* the styled-ASS switch on purpose: they apply to every subtitle
 * the app draws itself, while the switch below hands one format to a renderer that ignores them.
 */
@Composable
private fun SubtitleTextSizeGroup(
    selected: SubtitleTextSize,
    onSelect: (SubtitleTextSize) -> Unit,
) {
    val label = stringResource(R.string.settings_subtitle_size)
    SettingsChoiceGroup(
        label = label,
        supportingText = stringResource(R.string.settings_subtitle_appearance_supporting),
    ) {
        SubtitleTextSize.entries.forEach { size ->
            SettingsChoiceRow(
                groupLabel = label,
                label = stringResource(size.labelRes()),
                selected = size == selected,
                onSelect = { onSelect(size) },
            )
        }
    }
}

@Composable
private fun SubtitleBackgroundGroup(
    selected: SubtitleBackground,
    onSelect: (SubtitleBackground) -> Unit,
) {
    val label = stringResource(R.string.settings_subtitle_background)
    SettingsChoiceGroup(label = label) {
        SubtitleBackground.entries.forEach { background ->
            SettingsChoiceRow(
                groupLabel = label,
                label = stringResource(background.labelRes()),
                selected = background == selected,
                onSelect = { onSelect(background) },
            )
        }
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

// --- Downloads ----------------------------------------------------------------------------------

@Composable
private fun DownloadsPage(
    downloadOverWifiOnly: Boolean,
    downloadQuality: DownloadQuality,
    storage: StorageUsage,
    storageLocations: StorageLocations,
    onWifiOnly: (Boolean) -> Unit,
    onDownloadQuality: (DownloadQuality) -> Unit,
    onStorageLocation: (String, Boolean) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_eyebrow_on_this_device)) {
        StorageRow(usage = storage)
        StorageLocationGroup(locations = storageLocations, onSelect = onStorageLocation)
    }
    SettingsSection(title = stringResource(R.string.settings_eyebrow_over_the_network)) {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_wifi_only),
            supportingText = stringResource(R.string.settings_wifi_only_supporting),
            checked = downloadOverWifiOnly,
            onCheckedChange = onWifiOnly,
        )
    }
    SettingsSection(title = stringResource(R.string.settings_eyebrow_quality)) {
        DownloadQualityGroup(selected = downloadQuality, onSelect = onDownloadQuality)
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
    var pendingVolumeId by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsRowSeparator()

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
                        top = Dimens.SpaceSmall,
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
                bottom = Dimens.SpaceMedium,
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
        usedFraction = storageUsedFraction(usage.usedBytes, usage.availableBytes),
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
                    bottom = Dimens.SpaceMedium,
                ),
        )
    }
}

// --- Appearance ---------------------------------------------------------------------------------

/**
 * One panel, and therefore no eyebrow: a section heading only earns its place on a page carrying
 * more than one section, and here the screen title says what the panel is. The choice group keeps
 * its own "Theme" caption — that is the group's a11y anchor, repeated into each row's
 * `contentDescription`, not a heading.
 *
 * The dynamic-colour row is **absent**, not disabled, below API 31: the platform has no wallpaper
 * palette there, so the switch would be a control with nothing behind it.
 */
@Composable
private fun AppearancePage(
    themeMode: ThemeMode,
    dynamicColorEnabled: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    SettingsPanel {
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
        if (dynamicColorAvailable) {
            SettingsRowSeparator()
            SettingsSwitchRow(
                label = stringResource(R.string.settings_dynamic_color),
                supportingText = stringResource(R.string.settings_dynamic_color_supporting),
                checked = dynamicColorEnabled,
                onCheckedChange = onDynamicColor,
            )
        }
    }
}

// --- Network ------------------------------------------------------------------------------------

/** One panel, so no eyebrow — the screen title already names it. */
@Composable
private fun NetworkPage(
    forceOffline: Boolean,
    onForceOffline: (Boolean) -> Unit,
) {
    SettingsPanel {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_offline_mode),
            supportingText = stringResource(R.string.settings_offline_mode_supporting),
            checked = forceOffline,
            onCheckedChange = onForceOffline,
        )
    }
}

// --- Account ------------------------------------------------------------------------------------

/**
 * Who is signed in, to what, and the way out. Switching account and managing servers are absent:
 * both need a session layer that is keyed by server, and `getPendingSync()`, `downloads` and
 * `items` are single-identity (`docs/notes/m14-multiserver-design-brief.md`).
 *
 * The button holds a spinner and stops taking taps while a sign-out is in flight: telling an
 * unreachable server is capped at seconds (`SessionRepository.SERVER_GOODBYE_TIMEOUT`), and a button
 * that answers nothing for that long reads as broken.
 */
@Composable
private fun AccountPage(
    account: AccountInfo?,
    signingOut: Boolean,
    onSignOut: (Boolean) -> Unit,
) {
    // Saveable, and owned by the page rather than the shell: on a tablet the pane is the dialog's
    // host, so switching category dismisses it — which is what leaving the page should do — while a
    // rotation, which does not leave it, keeps it.
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }

    SettingsPanel {
        SettingsInfoRow(
            label = stringResource(R.string.settings_account_user),
            value = account?.userName ?: stringResource(R.string.settings_account_unknown),
        )
        if (account != null) {
            SettingsRowSeparator()
            SettingsInfoRow(
                label = stringResource(R.string.settings_account_server),
                value = account.serverName,
            )
        }
    }
    GhostPillButton(
        text = stringResource(R.string.settings_sign_out),
        // Once the sign-out is away there is nothing to confirm: it cannot be cancelled, and a
        // second one would delete the downloads twice.
        onClick = { if (!signingOut) confirmingSignOut = true },
        enabled = !signingOut,
        loading = signingOut,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
    )

    if (confirmingSignOut && !signingOut) {
        SignOutDialog(
            onDismiss = { confirmingSignOut = false },
            onConfirm = { deleteDownloads ->
                confirmingSignOut = false
                onSignOut(deleteDownloads)
            },
        )
    }
}

/**
 * The delete-downloads box is unchecked by default: a user signing out to switch accounts on a
 * shared tablet should not lose a weekend's downloads to a remembered answer.
 */
@Composable
private fun SignOutDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var deleteDownloads by rememberSaveable { mutableStateOf(false) }

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

// --- About --------------------------------------------------------------------------------------

/**
 * The three rows below the version are what makes the binary distributable: GPL-3.0 §4 requires the
 * licence to be conveyed with it, §6 requires the corresponding source to be offered, and the
 * bundled Apache-2.0 artifacts each require their own notice.
 */
@Composable
private fun AboutPage(
    appVersion: String,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
) {
    SettingsPanel {
        SettingsInfoRow(
            label = stringResource(R.string.settings_version),
            value = appVersion,
        )
        SettingsRowSeparator()
        SourceCodeRow()
        SettingsRowSeparator()
        SettingsActionRow(
            label = stringResource(R.string.settings_licence),
            supportingText = stringResource(R.string.settings_licence_name),
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            onClick = onOpenLicence,
        )
        SettingsRowSeparator()
        SettingsActionRow(
            label = stringResource(R.string.settings_third_party),
            supportingText = stringResource(R.string.settings_third_party_supporting),
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            onClick = onOpenThirdPartyLicences,
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

private fun SubtitleTextSize.labelRes(): Int =
    when (this) {
        SubtitleTextSize.SYSTEM -> R.string.settings_subtitle_follow_device
        SubtitleTextSize.SMALL -> R.string.settings_subtitle_size_small
        SubtitleTextSize.NORMAL -> R.string.settings_subtitle_size_normal
        SubtitleTextSize.LARGE -> R.string.settings_subtitle_size_large
        SubtitleTextSize.LARGER -> R.string.settings_subtitle_size_larger
    }

private fun SubtitleBackground.labelRes(): Int =
    when (this) {
        SubtitleBackground.SYSTEM -> R.string.settings_subtitle_follow_device
        SubtitleBackground.NONE -> R.string.settings_subtitle_background_none
        SubtitleBackground.TRANSLUCENT -> R.string.settings_subtitle_background_translucent
        SubtitleBackground.SOLID -> R.string.settings_subtitle_background_solid
    }

private fun DownloadQuality.labelRes(): Int =
    when (this) {
        DownloadQuality.ORIGINAL -> R.string.settings_quality_original
        DownloadQuality.HIGH -> R.string.settings_quality_high
        DownloadQuality.MEDIUM -> R.string.settings_quality_medium
        DownloadQuality.LOW -> R.string.settings_quality_low
    }
