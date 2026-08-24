package dev.jellyboost.player.session

import dev.jellyboost.core.network.isSameOrigin
import dev.jellyboost.core.network.jellyfinAuthorizationHeader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Without this header the server can attribute a transcode to a different session id, and
 * `stopEncodingProcess` then no longer finds it.
 *
 * Rebuilt per request: the access token changes over the app's lifetime while this OkHttp client
 * does not. Scheme, host **and** port must all match ([isSameOrigin]) or the token leaks off-server.
 *
 * Must stay a **network** interceptor: an application interceptor never sees redirect targets, so
 * the origin check would not run on them.
 */
@Singleton
internal class JellyfinAuthInterceptor
    @Inject
    constructor(
        private val apiClient: ApiClient,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val serverUrl = apiClient.baseUrl?.toHttpUrlOrNull()

            if (serverUrl == null || !request.url.isSameOrigin(serverUrl)) {
                return chain.proceed(request)
            }

            return chain.proceed(
                request
                    .newBuilder()
                    .header("Authorization", jellyfinAuthorizationHeader(apiClient))
                    .build(),
            )
        }
    }
