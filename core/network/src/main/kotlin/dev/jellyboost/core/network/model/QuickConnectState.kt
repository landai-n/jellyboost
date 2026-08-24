package dev.jellyboost.core.network.model

import dev.jellyboost.core.common.AppError

/**
 * The flow is finite: [WaitingForApproval] once per poll, then exactly one terminal value.
 */
sealed interface QuickConnectState {
    data object WaitingForApproval : QuickConnectState

    data object Approved : QuickConnectState

    /** The server dropped the request, or the client-side 5-minute cap elapsed. */
    data object Expired : QuickConnectState

    data class Failed(
        val error: AppError,
    ) : QuickConnectState
}
