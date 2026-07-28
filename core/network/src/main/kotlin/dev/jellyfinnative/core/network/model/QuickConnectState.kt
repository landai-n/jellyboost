package dev.jellyfinnative.core.network.model

import dev.jellyfinnative.core.common.AppError

/**
 * Progress of a Quick Connect request, as emitted by
 * `AuthRepository.observeQuickConnectState`.
 *
 * The flow is finite: it emits [WaitingForApproval] once per poll and then completes with
 * exactly one terminal value ([Approved], [Expired] or [Failed]).
 */
sealed interface QuickConnectState {
    /** The request exists but nobody has approved it yet. Keep showing the code. */
    data object WaitingForApproval : QuickConnectState

    /** The request was approved; exchange the secret via `loginWithQuickConnect`. */
    data object Approved : QuickConnectState

    /** The server dropped the request, or the client-side 5-minute cap elapsed. */
    data object Expired : QuickConnectState

    /** Polling stopped because of a transport/server failure. */
    data class Failed(
        val error: AppError,
    ) : QuickConnectState
}
