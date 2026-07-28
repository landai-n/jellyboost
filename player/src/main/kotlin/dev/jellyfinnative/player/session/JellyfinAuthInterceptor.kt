package dev.jellyfinnative.player.session

import okhttp3.Interceptor
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds the Jellyfin `Authorization` header to every stream request aimed at our own server.
 *
 * Streaming URLs already carry the token as a query parameter, but the header is what the server
 * uses to attribute the request to *this* session — without it a transcode can end up owned by a
 * different session id and `stopEncodingProcess` no longer finds it.
 *
 * The header is rebuilt per request rather than baked into the client because the access token
 * changes over the app's lifetime (sign-in, sign-out, server switch) while this OkHttp client does
 * not. Requests to other hosts — a redirect off-server, say — are left untouched so the token
 * never leaks.
 */
@Singleton
internal class JellyfinAuthInterceptor
    @Inject
    constructor(
        private val apiClient: ApiClient,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val serverHost = apiClient.baseUrl?.toHttpHostOrNull()

            if (serverHost == null || request.url.host != serverHost) {
                return chain.proceed(request)
            }

            val header =
                AuthorizationHeaderBuilder.buildHeader(
                    clientName = apiClient.clientInfo.name,
                    clientVersion = apiClient.clientInfo.version,
                    deviceId = apiClient.deviceInfo.id,
                    deviceName = apiClient.deviceInfo.name,
                    accessToken = apiClient.accessToken,
                )

            return chain.proceed(
                request
                    .newBuilder()
                    .header("Authorization", header)
                    .build(),
            )
        }
    }

/** Host part of a base URL, or `null` when it is not a URL we can parse. */
private fun String.toHttpHostOrNull(): String? = runCatching { java.net.URI(this).host }.getOrNull()
