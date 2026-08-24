package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.network.ApiClientProvider
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.net.HttpURLConnection
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

interface ServerProbeApi {
    /**
     * Returning the id rather than a boolean is a security requirement: the caller re-points the
     * **authenticated** client at whichever address answers, and any host on the network could 200 this
     * unauthenticated endpoint, so "something answered" must never be conflated with "our server answered".
     *
     * Never throws for an unreachable server — that is the expected outcome here, not an error.
     */
    suspend fun reachableServerId(baseUrl: String): UUID?
}

/**
 * `getPublicSystemInfo` is the right probe: unauthenticated (so it answers even with an expired token), tiny,
 * carries the server's id for the identity check, and is served by the pipeline real requests use.
 *
 * A **throwaway** `ApiClient` per probe: a candidate address must not re-point the live client before it is
 * known to work, and this client carries deliberately short timeouts where a normal request would sit on 30s.
 */
@Singleton
internal class SdkServerProbeApi
    @Inject
    constructor(
        private val apiClientProvider: ApiClientProvider,
    ) : ServerProbeApi {
        override suspend fun reachableServerId(baseUrl: String): UUID? =
            try {
                val client =
                    apiClientProvider.jellyfin.createApi(
                        baseUrl = baseUrl,
                        httpClientOptions = PROBE_CLIENT_OPTIONS,
                    )
                val response = client.systemApi.getPublicSystemInfo()
                if (response.status == HttpURLConnection.HTTP_OK) response.content.id?.toUUIDOrNull() else null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.d("Server probe failed for %s: %s", baseUrl, error.javaClass.simpleName)
                null
            }

        private companion object {
            /**
             * Every timeout pinned to the probe budget, so the call cannot outlive the `withTimeoutOrNull`
             * around it even if the transport declines to be cancelled.
             */
            val PROBE_CLIENT_OPTIONS =
                HttpClientOptions(
                    connectTimeout = ServerReachabilityProbe.PROBE_TIMEOUT_MS.milliseconds,
                    requestTimeout = ServerReachabilityProbe.PROBE_TIMEOUT_MS.milliseconds,
                    socketTimeout = ServerReachabilityProbe.PROBE_TIMEOUT_MS.milliseconds,
                )
        }
    }
