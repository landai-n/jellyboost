package dev.jellyboost.feature.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.SubtitleBackground
import dev.jellyboost.core.common.model.SubtitleTextSize
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.core.ui.component.ScreenHeader
import dev.jellyboost.core.ui.component.ScreenHeaderTitle
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage
import dev.jellyboost.data.downloads.model.StorageVolumeOption

/**
 * The settings **hub**: an identity row plus one row per category, each summarised by its own
 * current state. The preferences themselves live on the category pages this navigates to.
 *
 * Two shapes, one state. Below 840dp a row pushes the category's own destination ([onOpenCategory]);
 * at or above it the hub is a rail and the category opens in a pane beside it, chosen by local
 * saveable state rather than a route — so nothing on a tablet is a level deeper than on a phone.
 *
 * The header draws **Back only**: Home is a nav-bar destination, not a header button
 * (`design/foundations/navigation-chrome.html`).
 *
 * @param appVersion passed in because this module cannot see `:app`'s `BuildConfig`.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        actions = rememberSettingsActions(viewModel),
        onBack = onBack,
        onOpenCategory = onOpenCategory,
        onOpenAccount = onOpenAccount,
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
    val onStyledAssSubtitles: (Boolean) -> Unit,
    val onSubtitleTextSize: (SubtitleTextSize) -> Unit,
    val onSubtitleBackground: (SubtitleBackground) -> Unit,
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
 * Remembered against the ViewModel, not rebuilt per frame: a fresh bundle of eleven lambdas every
 * recomposition is a new parameter value for every page that takes it, which is what defeats
 * skipping under strong skipping.
 */
@Composable
internal fun rememberSettingsActions(viewModel: SettingsViewModel): SettingsActions =
    remember(viewModel) {
        SettingsActions(
            onIntroSkipMode = viewModel::setIntroSkipMode,
            onOutroSkipMode = viewModel::setOutroSkipMode,
            onPipOnLeave = viewModel::setPipOnLeave,
            onStyledAssSubtitles = viewModel::setStyledAssSubtitles,
            onSubtitleTextSize = viewModel::setSubtitleTextSize,
            onSubtitleBackground = viewModel::setSubtitleBackground,
            onWifiOnly = viewModel::setDownloadOverWifiOnly,
            onDownloadQuality = viewModel::setDownloadQuality,
            onStorageLocation = viewModel::setStorageLocation,
            onForceOffline = viewModel::setForceOffline,
            onThemeMode = viewModel::setThemeMode,
            onDynamicColor = viewModel::setDynamicColorEnabled,
            onSignOut = viewModel::signOut,
        )
    }

/**
 * The width cap matters on a tablet: unconstrained, the label sits at one edge and its switch at
 * the other, unreadable and unreachable one-handed.
 */
internal val SettingsContentMaxWidth: Dp = 640.dp

@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    // Hoisted **above** the width branch on purpose: parked inside the two-pane arm it would be
    // remembered by a host that a rotation into portrait destroys, and the tablet would come back
    // to Playback every time it was turned. Saveable, because that rotation recreates the activity.
    var openPane by rememberSaveable { mutableStateOf(SettingsPane.PLAYBACK) }
    val summaries =
        remember(state, appVersion) {
            hubSummaries(state, appVersion, dynamicColorAvailable)
        }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (isTwoPaneSettings(maxWidth)) {
            SettingsTwoPane(
                state = state,
                actions = actions,
                summaries = summaries,
                openPane = openPane,
                onOpenPane = { openPane = it },
                onBack = onBack,
                onOpenLicence = onOpenLicence,
                onOpenThirdPartyLicences = onOpenThirdPartyLicences,
                appVersion = appVersion,
            )
        } else {
            SettingsHubScreen(
                account = state.account,
                summaries = summaries,
                onBack = onBack,
                onOpenCategory = onOpenCategory,
                onOpenAccount = onOpenAccount,
            )
        }
    }
}

/** The compact shape: Back, the title, and the hub list, capped so it never spans a wide window. */
@Composable
private fun SettingsHubScreen(
    account: AccountInfo?,
    summaries: HubSummaries,
    onBack: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAccount: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(onBack = onBack) {
            ScreenHeaderTitle(text = stringResource(R.string.settings_title))
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SettingsHubPanels(
                account = account,
                summaries = summaries,
                onOpenAccount = onOpenAccount,
                onOpenCategory = onOpenCategory,
                modifier = Modifier.widthIn(max = SettingsContentMaxWidth),
            )
        }
    }
}

/**
 * At ≥840dp the rail carries the **only** Back on the screen and the pane carries none: a second
 * one beside it would look like a way out of the pane, which is not a place you can be.
 */
@Composable
private fun SettingsTwoPane(
    state: SettingsUiState,
    actions: SettingsActions,
    summaries: HubSummaries,
    openPane: SettingsPane,
    onOpenPane: (SettingsPane) -> Unit,
    onBack: () -> Unit,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    appVersion: String,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .width(SettingsRailWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
        ) {
            ScreenHeader(onBack = onBack) {
                ScreenHeaderTitle(
                    text = stringResource(R.string.settings_title),
                    style = JellyfinTypeExtras.ScreenTitleLarge,
                )
            }
            SettingsHubRail(
                account = state.account,
                summaries = summaries,
                openPane = openPane,
                onOpenPane = onOpenPane,
            )
        }
        VerticalDivider(
            thickness = GlassDefaults.HairlineWidth,
            color = GlassDefaults.PanelHairline,
        )
        SettingsPaneBody(
            pane = openPane,
            state = state,
            actions = actions,
            appVersion = appVersion,
            onOpenLicence = onOpenLicence,
            onOpenThirdPartyLicences = onOpenThirdPartyLicences,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsPaneBody(
    pane: SettingsPane,
    state: SettingsUiState,
    actions: SettingsActions,
    appVersion: String,
    onOpenLicence: () -> Unit,
    onOpenThirdPartyLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(horizontal = Dimens.SpaceExtraLarge, vertical = Dimens.SpaceExtraLarge),
    ) {
        Text(
            text = stringResource(pane.titleRes()),
            style = JellyfinTypeExtras.PaneTitle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier =
                Modifier
                    .padding(bottom = Dimens.SpaceMedium)
                    // The pane draws no header of its own, so this is where a heading jump lands.
                    .semantics { heading() },
        )
        SettingsCategoryBody(
            pane = pane,
            state = state,
            actions = actions,
            appVersion = appVersion,
            onOpenLicence = onOpenLicence,
            onOpenThirdPartyLicences = onOpenThirdPartyLicences,
            modifier = Modifier.widthIn(max = SettingsContentMaxWidth),
        )
    }
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

private val PreviewActions =
    SettingsActions(
        onIntroSkipMode = {},
        onOutroSkipMode = {},
        onPipOnLeave = {},
        onStyledAssSubtitles = {},
        onSubtitleTextSize = {},
        onSubtitleBackground = {},
        onWifiOnly = {},
        onDownloadQuality = {},
        onStorageLocation = { _, _ -> },
        onForceOffline = {},
        onThemeMode = {},
        onDynamicColor = {},
        onSignOut = {},
    )

@Preview(name = "Settings hub", showBackground = true, backgroundColor = 0xFF101010, heightDp = 720)
@Composable
private fun SettingsHubPreview() {
    JellyfinTheme {
        SettingsContent(
            state = PreviewState,
            actions = PreviewActions,
            onBack = {},
            onOpenCategory = {},
            onOpenAccount = {},
            onOpenLicence = {},
            onOpenThirdPartyLicences = {},
            appVersion = "0.1.0-debug",
        )
    }
}

@Preview(
    name = "Settings two-pane",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 1138,
    heightDp = 711,
)
@Composable
private fun SettingsTwoPanePreview() {
    JellyfinTheme {
        SettingsContent(
            state = PreviewState,
            actions = PreviewActions,
            onBack = {},
            onOpenCategory = {},
            onOpenAccount = {},
            onOpenLicence = {},
            onOpenThirdPartyLicences = {},
            appVersion = "0.1.0-debug",
        )
    }
}
