package dev.jellyfinnative.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.jellyfinnative.core.common.AppError

/**
 * The user-facing failures the auth screens can render.
 *
 * ViewModels map [AppError] onto this instead of onto a `String`, so that state stays free of
 * Android `Context` (and therefore unit-testable) while the actual copy lives in
 * `res/values/strings.xml`. [authErrorText] does the rendering.
 */
internal sealed interface AuthErrorMessage {
    /**
     * No candidate address for the typed input turned out to be a usable Jellyfin server.
     *
     * The two lists mirror jellyfin-android's `setup/ConnectionHelper.kt` error copy:
     * [unreachable] never answered, [incompatible] answered but is not a supported server.
     */
    data class ServerResolution(
        val unreachable: List<String>,
        val incompatible: List<String>,
    ) : AuthErrorMessage

    /** The input produced no address candidates at all, or nothing could be reached. */
    data object CannotConnect : AuthErrorMessage

    /** The server rejected the credentials. */
    data object InvalidCredentials : AuthErrorMessage

    /** The Quick Connect request went away before anybody approved it. */
    data object QuickConnectExpired : AuthErrorMessage

    /** The server answered, but with something we cannot act on. */
    data class ServerFailure(
        val statusCode: Int?,
    ) : AuthErrorMessage

    companion object {
        /** Maps a repository [error] onto the copy the auth screens show. */
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

/**
 * Renders [message] as the multi-line block the setup/login screens show below their inputs.
 *
 * The server-resolution variant is built exactly like jellyfin-android's: a "tried N candidates"
 * prefix, then a bulleted list per failure kind.
 */
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
