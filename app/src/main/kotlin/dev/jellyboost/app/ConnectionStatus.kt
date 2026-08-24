package dev.jellyboost.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * What the app's chrome says about a non-[ConnectionState.ONLINE] connection: the reason, and the
 * one thing the user can do about it.
 *
 * Kept as a plain enum rather than inlined into the composable so the mapping is unit-testable —
 * three reasons, three answers.
 *
 * It lives in its own file because the status icon is drawn by both the wide layout's
 * `GlassTopNav` and the compact layout's floating action cluster, and neither is a better home
 * for the table than the other.
 */
internal enum class ConnectionStatus(
    @param:StringRes val messageRes: Int,
    @param:StringRes val actionLabelRes: Int?,
) {
    /** No usable network at all; nothing to retry until one appears. */
    NO_NETWORK(R.string.offline_no_network, null),

    /** Network, but the server did not answer the probe — worth another try. */
    SERVER_UNREACHABLE(R.string.offline_server_unreachable, CoreUiR.string.state_retry),

    /** The user asked to be offline; the action turns it back off. */
    FORCED(R.string.offline_forced, R.string.offline_go_online),
}

/** `null` when the app is online — the chrome then draws no status icon at all. */
internal fun ConnectionState.toStatus(): ConnectionStatus? =
    when (this) {
        ConnectionState.ONLINE -> null
        ConnectionState.OFFLINE_NO_NETWORK -> ConnectionStatus.NO_NETWORK
        ConnectionState.OFFLINE_SERVER_UNREACHABLE -> ConnectionStatus.SERVER_UNREACHABLE
        ConnectionState.OFFLINE_FORCED -> ConnectionStatus.FORCED
    }

/** Each reason gets its own glyph, so the three states are told apart without reading any text. */
internal fun ConnectionStatus.icon(): ImageVector =
    when (this) {
        ConnectionStatus.NO_NETWORK -> Icons.Filled.WifiOff
        ConnectionStatus.SERVER_UNREACHABLE -> Icons.Filled.CloudOff
        ConnectionStatus.FORCED -> Icons.Filled.AirplanemodeActive
    }
