package dev.jellyboost.core.common

/**
 * Domain-level failure taxonomy. Every repository maps transport/SDK exceptions onto one of
 * these so that UI layers never have to reason about HTTP status codes or SDK exception types.
 */
sealed interface AppError {
    /** No usable network, or the server could not be reached. */
    data class Network(
        val cause: Throwable? = null,
    ) : AppError

    /** Credentials are missing, expired, or were rejected (HTTP 401). */
    data class Unauthorized(
        val cause: Throwable? = null,
    ) : AppError

    /** The requested item does not exist on the server, or is not cached offline. */
    data class NotFound(
        val id: String,
    ) : AppError

    /**
     * A user-entered server address produced address candidates, but none of them turned out to
     * be a usable Jellyfin server.
     *
     * The two lists mirror the split the server-setup screen renders (see jellyfin-android's
     * `setup/ConnectionHelper.kt` error copy): [unreachableAddresses] never answered at all,
     * [incompatibleAddresses] answered but are not a supported Jellyfin server. Both are empty
     * when the entered text yielded no candidates whatsoever.
     */
    data class ServerResolution(
        val unreachableAddresses: List<String> = emptyList(),
        val incompatibleAddresses: List<String> = emptyList(),
    ) : AppError

    /** The server answered, but with an error we cannot recover from. */
    data class Server(
        val statusCode: Int?,
        val cause: Throwable? = null,
    ) : AppError

    /** Local storage (Room, filesystem, SAF) failure. */
    data class Storage(
        val cause: Throwable? = null,
    ) : AppError

    /** Anything not covered above. */
    data class Unknown(
        val cause: Throwable? = null,
    ) : AppError
}
