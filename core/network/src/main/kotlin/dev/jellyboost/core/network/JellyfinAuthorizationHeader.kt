package dev.jellyboost.core.network

import okhttp3.HttpUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder

/**
 * Rebuilt on every call rather than cached: the access token changes over the app's lifetime (sign-in,
 * sign-out, server switch), so a cached copy would only ever be a stale one.
 */
fun jellyfinAuthorizationHeader(apiClient: ApiClient): String =
    AuthorizationHeaderBuilder.buildHeader(
        clientName = apiClient.clientInfo.name,
        clientVersion = apiClient.clientInfo.version,
        deviceId = apiClient.deviceInfo.id,
        deviceName = apiClient.deviceInfo.name,
        accessToken = apiClient.accessToken,
    )

/**
 * The same-origin guard the player's `JellyfinAuthInterceptor` runs before attaching
 * [jellyfinAuthorizationHeader]: a request is only "ours" when scheme, host and effective port all match the
 * server's base URL — a different port is a different service, and `http://` on an `https://` server would
 * put the token on the wire in clear.
 */
fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean = scheme == other.scheme && host == other.host && port == other.port
