package dev.jellyfinnative.feature.settings

import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.data.downloads.model.StorageUsage

/**
 * Everything the Settings screen draws.
 *
 * A flat projection of five DataStore keys, the download pipeline's storage figures and the live
 * session — the screen owns no state of its own, so a preference changed from anywhere else (the
 * home overflow's offline toggle, the Downloads tab's Wi-Fi switch) is already correct here.
 *
 * The defaults match the preference store's own defaults, so the first frame drawn before DataStore
 * has answered shows what the user is about to see rather than a flicker of the wrong switch.
 */
data class SettingsUiState(
    val introSkipMode: SegmentSkipMode = SegmentSkipMode.SHOW_BUTTON,
    val outroSkipMode: SegmentSkipMode = SegmentSkipMode.SHOW_BUTTON,
    val pipOnLeave: Boolean = true,
    val downloadOverWifiOnly: Boolean = true,
    val forceOffline: Boolean = false,
    val storage: StorageUsage = StorageUsage(),
    /** Who is signed in; `null` when the session is absent or still restoring. */
    val account: AccountInfo? = null,
)

/**
 * The signed-in user, as far as the settings screen is concerned.
 *
 * Only the two fields `SessionState.LoggedIn` can offer that mean anything to a user. The server
 * *address* is deliberately absent: it is not on the session state, and exposing it would mean
 * widening `:core:network`'s API for a line of text (see docs/features/settings.md).
 */
data class AccountInfo(
    val userName: String,
    val serverName: String,
)
