package dev.jellyfinnative.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the process-wide Jellyfin SDK objects: the [Jellyfin] entry point (used for server
 * discovery) and the single mutable [ApiClient] every repository issues calls through.
 *
 * There is exactly one [ApiClient] for the whole app rather than one per server: the SDK client
 * is designed to be re-pointed via [ApiClient.update], and a single instance keeps the OkHttp
 * connection pool, the configured base URL and the access token in one place. Repositories must
 * never construct their own client.
 *
 * Passing an Android `Context` into [createJellyfin] is what makes the SDK derive a stable
 * [org.jellyfin.sdk.model.DeviceInfo] (device id + name) for us — see [deviceId].
 */
@Singleton
class ApiClientProvider
    @Inject
    constructor(
        @ApplicationContext applicationContext: Context,
    ) {
        /** SDK entry point. Exposed for `jellyfin.discovery`; not for issuing API calls. */
        val jellyfin: Jellyfin =
            createJellyfin {
                context = applicationContext
                clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
            }

        /** The one API client. Its base URL and access token change over the app's lifetime. */
        val apiClient: ApiClient = jellyfin.createApi()

        /**
         * Stable per-installation device identifier the SDK derives from the Android context.
         *
         * This is the id the server shows under Dashboard → Devices, and the one playback
         * reporting and Quick Connect sessions are attributed to. `null` only if the SDK could
         * not build a `DeviceInfo`, which cannot happen for the Android platform build.
         */
        val deviceId: String? get() = jellyfin.deviceInfo?.id

        /**
         * Points the client at [baseUrl] with no credentials.
         *
         * Used for pre-authentication calls (public users, branding, Quick Connect availability)
         * and whenever the user switches to a different server.
         */
        fun useServer(baseUrl: String) {
            Timber.d("Pointing API client at %s (no credentials)", baseUrl)
            apiClient.update(baseUrl = baseUrl, accessToken = null)
        }

        /**
         * Points the client at [baseUrl] and authenticates it with [accessToken].
         *
         * The token is never logged here or anywhere else.
         */
        fun useSession(
            baseUrl: String,
            accessToken: String,
        ) {
            Timber.d("Pointing API client at %s with stored credentials", baseUrl)
            apiClient.update(baseUrl = baseUrl, accessToken = accessToken)
        }

        /** Drops the access token, keeping the current base URL. Used on sign-out. */
        fun clearSession() {
            apiClient.update(accessToken = null)
        }

        private companion object {
            /** Reported to the server as the client name; shown in Dashboard → Devices. */
            const val CLIENT_NAME = "jellyfin-native"

            /**
             * Client version reported to the server. Hard-coded for now; wire it to the app's
             * `versionName` once `:app` publishes one (M10, release hardening).
             */
            const val CLIENT_VERSION = "0.1.0"
        }
    }
