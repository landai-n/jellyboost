package dev.jellyboost.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.AppError

/**
 * ViewModels map [AppError] onto this rather than onto a `String`, so state stays free of a
 * `Context` and unit-testable; [authErrorText] does the rendering.
 */
internal sealed interface AuthErrorMessage {
    /** [unreachable] never answered; [incompatible] answered but is not a supported server. */
    data class ServerResolution(
        val unreachable: List<String>,
        val incompatible: List<String>,
    ) : AuthErrorMessage

    data object CannotConnect : AuthErrorMessage

    data object InvalidCredentials : AuthErrorMessage

    data object QuickConnectExpired : AuthErrorMessage

    data class ServerFailure(
        val statusCode: Int?,
    ) : AuthErrorMessage

    companion object {
        fun from(error: AppError): AuthErrorMessage =
            when (error) {
                is AppError.ServerResolution ->
                    if (error.unreachableAddresses.isEmpty() && error.incompatibleAddresses.isEmpty()) {
                        CannotConnect
                    } else {
                        ServerResolution(
                            unreachable = error.unreachableAddresses,
                            incompatible = error.incompatibleAddresses,
                        )
                    }

                is AppError.Unauthorized -> InvalidCredentials
                is AppError.Network -> CannotConnect
                is AppError.Server -> ServerFailure(error.statusCode)
                is AppError.NotFound -> ServerFailure(statusCode = null)
                is AppError.Storage -> ServerFailure(statusCode = null)
                is AppError.Unknown -> ServerFailure(statusCode = null)
            }
    }
}

@Composable
internal fun authErrorText(message: AuthErrorMessage): String =
    when (message) {
        is AuthErrorMessage.ServerResolution -> serverResolutionText(message)
        AuthErrorMessage.CannotConnect -> stringResource(R.string.auth_error_cannot_connect)
        AuthErrorMessage.InvalidCredentials -> stringResource(R.string.auth_error_invalid_credentials)
        AuthErrorMessage.QuickConnectExpired -> stringResource(R.string.auth_error_quick_connect_expired)
        is AuthErrorMessage.ServerFailure ->
            message.statusCode?.let { code ->
                stringResource(R.string.auth_error_server_with_code, code)
            } ?: stringResource(R.string.auth_error_server)
    }

@Composable
private fun serverResolutionText(message: AuthErrorMessage.ServerResolution): String {
    val candidateCount = message.unreachable.size + message.incompatible.size
    val prefix = pluralStringResource(R.plurals.auth_error_candidates_tried, candidateCount, candidateCount)
    val unreachableLabel = stringResource(R.string.auth_error_unable_to_reach_server)
    val incompatibleLabel = stringResource(R.string.auth_error_unsupported_version_or_product)

    return buildString {
        append(prefix)
        appendAddresses(unreachableLabel, message.unreachable)
        appendAddresses(incompatibleLabel, message.incompatible)
    }
}

private fun StringBuilder.appendAddresses(
    label: String,
    addresses: List<String>,
) {
    if (addresses.isEmpty()) return
    append("\n\n")
    append(label)
    append(":\n")
    append(addresses.joinToString(separator = "\n") { address -> "· $address" })
}
