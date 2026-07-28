package dev.jellyfinnative.core.common

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
