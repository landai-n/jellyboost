package dev.jellyboost.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.datastore.DeviceIdProvider
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the process-wide Jellyfin SDK objects. There is exactly one [ApiClient] for the whole app rather than
 * one per server — it is designed to be re-pointed via [ApiClient.update], and one instance keeps the OkHttp
 * pool, base URL and token in one place. Repositories must never construct their own client.
 *
 * The device *id* is deliberately NOT the SDK's default — see [deviceId].
 *
 * Both SDK objects are built on first *use*: construction costs a blocking `SharedPreferences` read (plus a
 * `commit()` fsync on first run), a `Settings.Global` binder read and Ktor/OkHttp setup, which anything
 * injecting this class would otherwise pay on the cold-start path. `by lazy`'s default mode makes it thread-safe.
 */
@Singleton
class ApiClientProvider
    @Inject
    constructor(
        @ApplicationContext private val applicationContext: Context,
        private val deviceIdProvider: DeviceIdProvider,
    ) {
        /** Exposed for `jellyfin.discovery`; not for issuing API calls. */
        val jellyfin: Jellyfin by lazy {
            createJellyfin {
                context = applicationContext
                clientInfo = ClientInfo(name = CLIENT_NAME, version = CLIENT_VERSION)
                deviceInfo =
                    DeviceInfo(
                        id = deviceIdProvider.deviceId,
                        name = androidDevice(applicationContext).name,
                    )
            }
        }

        val apiClient: ApiClient by lazy { jellyfin.createApi() }

        /**
         * A random UUID persisted on first run, not the SDK's `ANDROID_ID` default: the SSAID is scoped per
         * signing key rather than per package, and a Jellyfin server keeps one token per (user, device id), so
         * debug and locally-signed release installs kept revoking each other's session.
         *
         * This is the id the server shows under Dashboard → Devices, and what playback reporting and Quick
         * Connect sessions are attributed to.
         */
        val deviceId: String? get() = jellyfin.deviceInfo?.id

        /** Drops any credentials — for pre-authentication calls, and when switching to a different server. */
        fun useServer(baseUrl: String) {
            Timber.d("Pointing API client at %s (no credentials)", hostForLog(baseUrl))
            apiClient.update(baseUrl = baseUrl, accessToken = null)
        }

        /** The token is never logged here or anywhere else. */
        fun useSession(
            baseUrl: String,
            accessToken: String,
        ) {
            Timber.d("Pointing API client at %s with stored credentials", hostForLog(baseUrl))
            apiClient.update(baseUrl = baseUrl, accessToken = accessToken)
        }

        /**
         * Keeps the current credentials — used when the server answered on another of its known addresses
         * (LAN ↔ remote). Distinct from [useServer], which drops the token because it changes *server*.
         */
        fun useAddress(baseUrl: String) {
            Timber.d("Re-pointing API client at %s, keeping the session", hostForLog(baseUrl))
            apiClient.update(baseUrl = baseUrl)
        }

        fun clearSession() {
            apiClient.update(accessToken = null)
        }

        private companion object {
            /** Reported to the server; shown in Dashboard → Devices. */
            const val CLIENT_NAME = "Jellyboost"

            /** TODO: wire to the app's `versionName` once `:app` publishes one. */
            const val CLIENT_VERSION = "0.1.0"
        }
    }
