package dev.jellyboost.core.network.session

import dev.jellyboost.core.network.SessionRepository
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes sure the API client is pointed at a server before a `WorkManager` worker tries to use it.
 *
 * WorkManager can restart a job before the UI has done anything — `MainViewModel` calls `restoreSession()`
 * from its `init`, and on a cold start a worker can win that race. Without this gate the worker's first call
 * throws the SDK's `Required value baseUrl is null`, which reads as a failure of the work itself. It gates
 * every worker, not one.
 *
 * Safe to call from a worker: [SessionRepository.restoreSession] is local-only (credential store plus two Room
 * reads, no network) and idempotent.
 */
@Singleton
class SessionGate
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val apiClient: ApiClient,
    ) {
        /**
         * @return `true` when the client can build URLs and authenticate, `false` when this device genuinely
         *   has no session. The caller must park rather than fail: work never attempted has nothing to report.
         */
        suspend fun ensureSession(): Boolean {
            if (isUsable()) return true

            Timber.i("Work started before the session was restored; restoring it now")
            sessionRepository.restoreSession()

            return isUsable().also { restored ->
                if (!restored) Timber.w("No stored session to restore; the work stays parked")
            }
        }

        /**
         * Both halves matter: the base URL is what the SDK's URL builders require, the token is what an
         * `Authorization` header is built from. A client with one and not the other fails later and less legibly.
         */
        private fun isUsable(): Boolean = !apiClient.baseUrl.isNullOrBlank() && !apiClient.accessToken.isNullOrBlank()
    }
