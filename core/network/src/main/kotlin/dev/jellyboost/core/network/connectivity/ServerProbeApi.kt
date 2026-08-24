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

/**
 * The single SDK call the reachability probe makes, behind a seam so the probe's candidate-rotation
 * logic can be unit-tested without a server.
 */
interface ServerProbeApi {
    /**
     * The id of the Jellyfin server answering `getPublicSystemInfo` at [baseUrl] within
     * [ServerReachabilityProbe.PROBE_TIMEOUT_MS], or `null` when nothing usable answers.
     *
     * Returning the id rather than a boolean is a security requirement, not a convenience: the
     * probe's caller re-points the **authenticated** client at whichever address answers, so
     * "something answered" must never be conflated with "our server answered" — any host on the
     * current network could 200 this unauthenticated endpoint.
     *
     * Never throws for an unreachable server — an unreachable server is the expected outcome here,
     * not an error.
     */
    suspend fun reachableServerId(baseUrl: String): UUID?
}

/**
 * [ServerProbeApi] on jellyfin-sdk.
 *
 * `getPublicSystemInfo` is the right probe: it is unauthenticated (so it still answers with an
 * expired token), tiny, carries the server's id for the identity check, and is served by the same
 * pipeline real requests use, so a server that answers it is genuinely usable.
 *
 * A **throwaway** `ApiClient` is created per probe rather than reusing the app's one. Two reasons:
 * probing a candidate address must not re-point the live client before we know it works, and this
 * client carries deliberately short timeouts — the whole point of the probe is to fail in seconds
 * where a normal request would sit on a 30-second socket timeout.
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
