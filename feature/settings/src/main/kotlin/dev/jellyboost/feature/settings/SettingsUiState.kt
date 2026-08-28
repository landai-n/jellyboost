package dev.jellyboost.feature.settings

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage

/**
 * The screen owns no state of its own, so a preference changed elsewhere is already correct here.
 * The defaults match the preference store's, so the first frame is not a flicker of wrong switches.
 */
data class SettingsUiState(
    val introSkipMode: SegmentSkipMode = SegmentSkipMode.SHOW_BUTTON,
    val outroSkipMode: SegmentSkipMode = SegmentSkipMode.SHOW_BUTTON,
    val pipOnLeave: Boolean = true,
    val downloadOverWifiOnly: Boolean = true,
    /** What future downloads are fetched at; the running queue keeps whatever it was enqueued with. */
    val downloadQuality: DownloadQuality = DownloadQuality.ORIGINAL,
    val forceOffline: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Ignored below API 31, where the row is not drawn at all. */
    val dynamicColorEnabled: Boolean = false,
    val storage: StorageUsage = StorageUsage(),
    val storageLocations: StorageLocations = StorageLocations(),
    val account: AccountInfo? = null,
    /**
     * Only ever goes up: telling an unreachable server the session ended takes seconds, and the
     * sign-out completing navigates away, so there is nothing to lower it for.
     */
    val signingOut: Boolean = false,
)

/**
 * The server *address* is deliberately absent: it is not on the session state, and exposing it
 * would mean widening `:core:network`'s API for a line of text.
 */
data class AccountInfo(
    val userName: String,
    val serverName: String,
)
