package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.network.ApiClientProvider
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.systemApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single SDK call the reachability probe makes, behind a seam so the probe's candidate-rotation
 * logic can be unit-tested without a server.
 */
interface ServerProbeApi {
    /**
     * `true` when a Jellyfin server answers `getPublicSystemInfo` at [baseUrl] within
     * [ServerReachabilityProbe.PROBE_TIMEOUT_MS].
     *
     * Never throws for an unreachable server — an unreachable server is the expected outcome here,
     * not an error.
     */
    suspend fun isReachable(baseUrl: String): Boolean
}

/**
 * [ServerProbeApi] on jellyfin-sdk.
 *
 * `getPublicSystemInfo` is the right probe: it is unauthenticated (so it still answers with an
 * expired token), tiny, and served by the same pipeline real requests use, so a server that answers
 * it is genuinely usable.
 *
 * A **throwaway** `ApiClient` is created per probe rather than reusing the app's one. Two reasons:
 * probing a candidate address must not re-point the live client before we know it works, and this
 * client carries deliberately short timeouts — the whole point of the probe is to fail in seconds
 * where a normal request would sit on a 30-second socket timeout (docs/PLAN.md, "Connectivity").
 */
@Singleton
internal class SdkServerProbeApi
    @Inject
    constructor(
        private val apiClientProvider: ApiClientProvider,
    ) : ServerProbeApi {
        override suspend fun isReachable(baseUrl: String): Boolean =
            try {
                val client =
                    apiClientProvider.jellyfin.createApi(
                        baseUrl = baseUrl,
                        httpClientOptions = PROBE_CLIENT_OPTIONS,
                    )
                client.systemApi.getPublicSystemInfo().status == HTTP_OK
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.d("Server probe failed for %s: %s", baseUrl, error.javaClass.simpleName)
                false
            }

        private companion object {
            const val HTTP_OK = 200

            /**
             * Every timeout pinned to the probe budget, so the call cannot outlive the
             * `withTimeoutOrNull` around it even if the transport declines to be cancelled.
             */
            val PROBE_CLIENT_OPTIONS =
                HttpClientOptions(
                    connectTimeout = ServerReachabilityProbe.PROBE_TIMEOUT_MS.milliseconds,
                    requestTimeout = ServerReachabilityProbe.PROBE_TIMEOUT_MS.milliseconds,
                    socketTimeout = ServerReachabilityProbe.PROBE_TIMEOUT_MS.milliseconds,
                )
        }
    }
