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
 * Owns the process-wide Jellyfin SDK objects: the [Jellyfin] entry point (used for server
 * discovery) and the single mutable [ApiClient] every repository issues calls through.
 *
 * There is exactly one [ApiClient] for the whole app rather than one per server: the SDK client
 * is designed to be re-pointed via [ApiClient.update], and a single instance keeps the OkHttp
 * connection pool, the configured base URL and the access token in one place. Repositories must
 * never construct their own client.
 *
 * The Android `Context` gives the SDK the device *name* it derives from `Build`/`device_name`.
 * The device *id* is deliberately NOT the SDK's default — see [deviceId].
 *
 * ### Nothing happens in the constructor (audit PERF-2)
 * Both SDK objects are built on first *use*, not on construction. Building them is the most
 * expensive thing in the app's singleton graph — a blocking `SharedPreferences` XML read plus, on
 * the very first run, a synchronous `commit()` fsync (`SharedPreferencesDeviceIdStore`), a
 * `Settings.Global` binder read for the device name, and Ktor/OkHttp/serialization setup — and
 * anything that injects *anything* reaching this class used to pay all of it. Whoever first touches
 * [jellyfin] or [apiClient] pays it instead, off the cold-start path; `by lazy`'s default
 * synchronized mode makes that safe from any thread.
 */
@Singleton
class ApiClientProvider
    @Inject
    constructor(
        @ApplicationContext private val applicationContext: Context,
        private val deviceIdProvider: DeviceIdProvider,
    ) {
        /** SDK entry point. Exposed for `jellyfin.discovery`; not for issuing API calls. */
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

        /** The one API client. Its base URL and access token change over the app's lifetime. */
        val apiClient: ApiClient by lazy { jellyfin.createApi() }

        /**
         * Stable per-installation device identifier, from [DeviceIdProvider] (a random UUID
         * persisted on first run) rather than the SDK's `ANDROID_ID` default.
         *
         * The default would be shared by every app signed with the same key — Android scopes the
         * SSAID per signing key, not per package — and since a Jellyfin server keeps one token per
         * (user, device id), our debug and locally-signed release installs kept revoking each
         * other's session. See `DeviceIdProvider`.
         *
         * This is the id the server shows under Dashboard → Devices, and the one playback
         * reporting and Quick Connect sessions are attributed to. `null` only if the SDK could
         * not build a `DeviceInfo`, which cannot happen now that one is supplied explicitly.
         */
        val deviceId: String? get() = jellyfin.deviceInfo?.id

        /**
         * Points the client at [baseUrl] with no credentials.
         *
         * Used for pre-authentication calls (public users, branding, Quick Connect availability)
         * and whenever the user switches to a different server.
         */
        fun useServer(baseUrl: String) {
            Timber.d("Pointing API client at %s (no credentials)", hostForLog(baseUrl))
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
            Timber.d("Pointing API client at %s with stored credentials", hostForLog(baseUrl))
            apiClient.update(baseUrl = baseUrl, accessToken = accessToken)
        }

        /**
         * Re-points the client at [baseUrl] **keeping the current credentials**.
         *
         * Used by `ServerReachabilityProbe` when the server answered on a different one of its
         * known addresses (LAN ↔ remote). Distinct from [useServer], which drops the token because
         * it is used when switching to a different *server*.
         */
        fun useAddress(baseUrl: String) {
            Timber.d("Re-pointing API client at %s, keeping the session", hostForLog(baseUrl))
            apiClient.update(baseUrl = baseUrl)
        }

        /** Drops the access token, keeping the current base URL. Used on sign-out. */
        fun clearSession() {
            apiClient.update(accessToken = null)
        }

        private companion object {
            /** Reported to the server as the client name; shown in Dashboard → Devices. */
            const val CLIENT_NAME = "Jellyboost"

            /**
             * Client version reported to the server. Hard-coded for now; wire it to the app's
             * `versionName` once `:app` publishes one (M10, release hardening).
             */
            const val CLIENT_VERSION = "0.1.0"
        }
    }
