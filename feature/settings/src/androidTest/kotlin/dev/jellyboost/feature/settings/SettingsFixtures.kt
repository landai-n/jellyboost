package dev.jellyboost.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage
import dev.jellyboost.data.downloads.model.StorageVolumeOption

internal val TWO_VOLUMES =
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
    )

internal val TEST_STATE =
    SettingsUiState(
        introSkipMode = SegmentSkipMode.AUTO_SKIP,
        outroSkipMode = SegmentSkipMode.SHOW_BUTTON,
        pipOnLeave = true,
        styledAssSubtitles = false,
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
        storageLocations = TWO_VOLUMES,
        account = AccountInfo(userName = "casey", serverName = "test-server"),
    )

internal val NO_OP_ACTIONS =
    SettingsActions(
        onIntroSkipMode = {},
        onOutroSkipMode = {},
        onPipOnLeave = {},
        onStyledAssSubtitles = {},
        onWifiOnly = {},
        onDownloadQuality = {},
        onStorageLocation = { _, _ -> },
        onForceOffline = {},
        onThemeMode = {},
        onDynamicColor = {},
        onSignOut = {},
    )

internal const val TEST_APP_VERSION = "0.1.0-debug"

/**
 * A viewport of a stated size at a stated font scale. `requiredSize` rather than `size`: the
 * `BoxWithConstraints` inside must be told a width the device may not have, which is the only way
 * to compose the two-pane arm on a phone-sized emulator.
 */
@Composable
internal fun TestViewport(
    width: Dp,
    height: Dp = 900.dp,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = fontScale),
    ) {
        JellyfinTheme {
            Box(modifier = Modifier.requiredSize(width = width, height = height)) {
                content()
            }
        }
    }
}
