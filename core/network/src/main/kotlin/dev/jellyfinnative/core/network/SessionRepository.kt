package dev.jellyfinnative.core.network

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.database.dao.ServerDao
import dev.jellyfinnative.core.database.dao.UserDao
import dev.jellyfinnative.core.datastore.SecureCredentialStore
import dev.jellyfinnative.core.network.model.SessionState
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of an already-established session: restoring it on app start and tearing
 * it down on sign-out (docs/PLAN.md, M1).
 *
 * Restore is deliberately network-free — it reads the token from [SecureCredentialStore] and
 * the server/user details from Room — so the app comes up signed in with no connectivity at
 * all, which is what the offline story depends on.
 */
@Singleton
class SessionRepository
    @Inject
    internal constructor(
        private val apiFacade: JellyfinApiFacade,
        private val apiClientProvider: ApiClientProvider,
        private val serverDao: ServerDao,
        private val userDao: UserDao,
        private val secureCredentialStore: SecureCredentialStore,
        private val sessionStateHolder: SessionStateHolder,
    ) {
        /**
         * Current session. Starts at [SessionState.Unknown]; hold the splash screen until it
         * becomes something else.
         */
        val sessionState: StateFlow<SessionState> = sessionStateHolder.state

        /**
         * Restores the stored session, if any, and configures the API client for it.
         *
         * A stored token whose server or user rows are gone is an inconsistent state (a wiped
         * database, a restored backup): the token is discarded and the user signs in again.
         * A transient storage failure, by contrast, leaves the stored session alone and only
         * reports [SessionState.LoggedOut] for this app run.
         */
        suspend fun restoreSession() {
            when (val result = storageCall { readStoredSession() }) {
                is AppResult.Success -> sessionStateHolder.update(result.value)
                is AppResult.Failure -> {
                    Timber.e("Could not read the stored session: %s", result.error)
                    sessionStateHolder.update(SessionState.LoggedOut)
                }
            }
        }

        /**
         * Signs out: tells the server the session ended (best effort — a failure here must not
         * strand the user in a signed-in UI), wipes [SecureCredentialStore], drops the token
         * from the API client and reports [SessionState.LoggedOut].
         *
         * Server and user rows stay in Room so the next sign-in on the same server is instant;
         * the plan only requires the credential store to be cleared.
         */
        suspend fun signOut() {
            val reported = apiCall { apiFacade.reportSessionEnded() }
            if (reported is AppResult.Failure) {
                Timber.w("Could not report session end to the server: %s", reported.error)
            }

            val cleared = storageCall { secureCredentialStore.clear() }
            if (cleared is AppResult.Failure) {
                Timber.e("Could not clear the stored credentials: %s", cleared.error)
            }

            apiClientProvider.clearSession()
            sessionStateHolder.update(SessionState.LoggedOut)
            Timber.i("Signed out")
        }

        private suspend fun readStoredSession(): SessionState {
            val stored = secureCredentialStore.read()
            if (stored == null) {
                Timber.i("No stored session; starting signed out")
                return SessionState.LoggedOut
            }

            val server = serverDao.getServer(stored.serverId)
            val user = userDao.getUser(stored.userId)
            val address = serverDao.getAddresses(stored.serverId).firstOrNull()?.address

            if (server == null || user == null || address == null) {
                Timber.w("Stored session has no matching server/user rows; discarding it")
                secureCredentialStore.clear()
                return SessionState.LoggedOut
            }

            apiClientProvider.useSession(address, stored.accessToken)
            Timber.i("Restored session for '%s' on '%s'", user.name, server.name)

            return SessionState.LoggedIn(
                serverId = server.id,
                userId = user.id,
                userName = user.name,
                serverName = server.name,
                serverVersion = server.version,
            )
        }
    }
