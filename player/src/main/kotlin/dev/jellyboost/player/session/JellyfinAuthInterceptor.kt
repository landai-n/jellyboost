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
 * Adds the Jellyfin `Authorization` header to every stream request aimed at our own server.
 *
 * Streaming URLs already carry the token as a query parameter, but the header is what the server
 * uses to attribute the request to *this* session — without it a transcode can end up owned by a
 * different session id and `stopEncodingProcess` no longer finds it.
 *
 * The header is rebuilt per request rather than baked into the client because the access token
 * changes over the app's lifetime (sign-in, sign-out, server switch) while this OkHttp client does
 * not. A request is only "ours" when its scheme, host **and** effective port all match the base
 * URL ([isSameOrigin], audit NET-04) — a different port is a different service, and `http://` on an
 * `https://` server would put the token on the wire in clear. Anything else is left untouched so
 * the token never leaks.
 *
 * Registered as a **network** interceptor (audit NET-05), so the check runs once per hop rather
 * than once per call: a redirect's target goes through it too, and only earns the header if it is
 * still our server. As an application interceptor the origin-URL check would never see redirect
 * targets and the off-server guarantee would rest silently on OkHttp's own header stripping.
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
