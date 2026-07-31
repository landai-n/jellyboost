package dev.jellyboost.core.network.session

import dev.jellyboost.core.network.SessionRepository
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes sure the API client is pointed at a server before a `WorkManager` worker tries to use it.
 *
 * ### Why this exists
 * WorkManager can restart a job as soon as the process comes up, which is *before* the UI has done
 * anything at all — `MainViewModel` calls `SessionRepository.restoreSession()` from its `init`, and
 * on a cold start after `am force-stop` a worker can win that race. Without this gate the first call
 * a worker makes throws the SDK's own `Required value baseUrl is null. Provide it by setting
 * ApiClient.baseUrl.`, which reads as a genuine failure of whatever the worker was doing even though
 * nothing was actually wrong with it — the session just had not been restored yet. This is generic to
 * any worker, not specific to one: it first shipped for `DownloadWorker` (M7), and now also gates
 * `UserDataSyncWorker` (M8), after the same race showed up in the sync drain on the M8 device walk.
 *
 * ### Why it is safe to call from a worker
 * [SessionRepository.restoreSession] is the M1 local-only path: `EncryptedSharedPreferences` plus
 * two Room reads, no network. Calling it twice is harmless — it is idempotent, and the second call
 * simply re-points the client at the address it already has.
 */
@Singleton
class SessionGate
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val apiClient: ApiClient,
    ) {
        /**
         * Restores the stored session if the API client has none.
         *
         * @return `true` when the client can build URLs and authenticate, `false` when this device
         *   genuinely has no session (signed out, or the credential store could not be read). The
         *   caller must park rather than fail: work that has never been attempted has nothing to
         *   report to the user.
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
         * Both halves matter: the base URL is what the SDK's URL builders require, and the token is
         * what an `Authorization` header is built from. A client with one and not the other would
         * fail later and less legibly.
         */
        private fun isUsable(): Boolean = !apiClient.baseUrl.isNullOrBlank() && !apiClient.accessToken.isNullOrBlank()
    }
