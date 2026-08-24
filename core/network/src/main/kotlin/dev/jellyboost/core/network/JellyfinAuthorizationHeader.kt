package dev.jellyboost.core.network

import okhttp3.HttpUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder

/**
 * The Jellyfin `Authorization` header value for the current session on [apiClient].
 *
 * Rebuilt on every call rather than cached: the access token changes over the app's lifetime
 * (sign-in, sign-out, server switch), and every caller of this function reads it right before
 * attaching it to a request that is about to go out, so a cached copy would only ever be a stale
 * one.
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
 * Same scheme, host and effective port — `HttpUrl.port` already fills in the scheme default.
 *
 * This is the same-origin guard the player's `JellyfinAuthInterceptor` runs before it will attach
 * [jellyfinAuthorizationHeader] to a request: a request is only "ours" when all three match the
 * server's own base URL — a different port is a different service, and `http://`
 * on an `https://` server would put the token on the wire in clear. The interceptor's own KDoc has
 * the full reasoning; the other two call sites of [jellyfinAuthorizationHeader] build their request
 * URL directly from this same [ApiClient] rather than from redirect-followed, caller-supplied ones,
 * so there is no other origin an attached header could leak to and they do not run this guard.
 */
fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean = scheme == other.scheme && host == other.host && port == other.port
