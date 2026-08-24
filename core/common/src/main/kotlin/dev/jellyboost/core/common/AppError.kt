package dev.jellyboost.core.common

/** Every repository maps transport/SDK exceptions onto one of these, so no UI layer reasons about status codes. */
sealed interface AppError {
    data class Network(
        val cause: Throwable? = null,
    ) : AppError

    data class Unauthorized(
        val cause: Throwable? = null,
    ) : AppError

    data class NotFound(
        val id: String,
    ) : AppError

    /**
     * [unreachableAddresses] never answered at all, [incompatibleAddresses] answered but are not a supported
     * Jellyfin server — the split the server-setup screen's error copy renders. Both empty when the entered
     * text yielded no candidates whatsoever.
     */
    data class ServerResolution(
        val unreachableAddresses: List<String> = emptyList(),
        val incompatibleAddresses: List<String> = emptyList(),
    ) : AppError

    data class Server(
        val statusCode: Int?,
        val cause: Throwable? = null,
    ) : AppError

    data class Storage(
        val cause: Throwable? = null,
    ) : AppError

    data class Unknown(
        val cause: Throwable? = null,
    ) : AppError
}
