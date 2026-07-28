package dev.jellyfinnative.data.downloads.work

import dev.jellyfinnative.core.network.SessionRepository
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes sure the API client is pointed at a server before the download queue tries to use it.
 *
 * ### Why this exists
 * WorkManager restarts the download job as soon as the process comes up, which is *before* the UI
 * has done anything at all — `MainViewModel` calls `SessionRepository.restoreSession()` from its
 * `init`, and on a cold start after `am force-stop` the worker wins that race. Without this gate the
 * first URL the file plan builds throws the SDK's own
 * `Required value baseUrl is null. Provide it by setting ApiClient.baseUrl.`, the queue records it
 * as a download failure, and the user has to press *Retry* on an item that was never actually
 * broken — which is exactly what M7's "resumes from byte offset after app kill" is supposed to do
 * by itself.
 *
 * ### Why it is safe to call from a worker
 * [SessionRepository.restoreSession] is the M1 local-only path: `EncryptedSharedPreferences` plus
 * two Room reads, no network. Calling it twice is harmless — it is idempotent, and the second call
 * simply re-points the client at the address it already has.
 */
@Singleton
class DownloadSessionGate
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
         *   caller must park rather than fail: an item that has never been attempted has nothing to
         *   report to the user.
         */
        suspend fun ensureSession(): Boolean {
            if (isUsable()) return true

            Timber.i("Download queue started before the session was restored; restoring it now")
            sessionRepository.restoreSession()

            return isUsable().also { restored ->
                if (!restored) Timber.w("No stored session to restore; the download queue stays parked")
            }
        }

        /**
         * Both halves matter: the base URL is what the SDK's URL builders require, and the token is
         * what `FileDownloader`'s `Authorization` header is built from. A client with one and not
         * the other would fail later and less legibly.
         */
        private fun isUsable(): Boolean = !apiClient.baseUrl.isNullOrBlank() && !apiClient.accessToken.isNullOrBlank()
    }
