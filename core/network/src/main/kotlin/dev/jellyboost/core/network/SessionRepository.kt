package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.datastore.HomeLayoutStore
import dev.jellyboost.core.datastore.SecureCredentialStore
import dev.jellyboost.core.network.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Restore is deliberately network-free — token from [SecureCredentialStore], server/user rows from Room —
 * so the app comes up signed in with no connectivity at all, which is what the offline story depends on.
 */
@Singleton
class SessionRepository
    @Suppress(
        "LongParameterList",
    )
    @Inject
    internal constructor(
        private val apiFacade: JellyfinApiFacade,
        private val apiClientProvider: ApiClientProvider,
        private val serverDao: ServerDao,
        private val userDao: UserDao,
        private val userDataDao: UserDataDao,
        private val itemDao: ItemDao,
        private val secureCredentialStore: SecureCredentialStore,
        private val sessionStateHolder: SessionStateHolder,
        private val homeLayoutStore: HomeLayoutStore,
        private val signOutHooks: Set<@JvmSuppressWildcards SignOutHook>,
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        /** Starts at [SessionState.Unknown]; hold the splash screen until it becomes something else. */
        val sessionState: StateFlow<SessionState> = sessionStateHolder.state

        private val involuntarySignOut = AtomicBoolean(false)

        /**
         * A stored token whose server or user rows are gone is an inconsistent state (wiped database,
         * restored backup): the token is discarded. A transient storage failure, by contrast, leaves the
         * stored session alone and only reports [SessionState.LoggedOut] for this app run.
         *
         * Every path that signs the user out **without them asking** records it — see
         * [consumeInvoluntarySignOut]. Having *no* stored session is not one of those paths: that is a first run.
         */
        suspend fun restoreSession() {
            when (val result = storageCall { readStoredSession() }) {
                is AppResult.Success -> sessionStateHolder.update(result.value)
                is AppResult.Failure -> {
                    Timber.e("Could not read the stored session: %s", result.error)
                    involuntarySignOut.set(true)
                    sessionStateHolder.update(SessionState.LoggedOut)
                }
            }
        }

        /**
         * Reading it clears the flag, so the message it drives is shown once. A poll rather than an event
         * stream because the splash is held until [restoreSession] has finished (`MainActivity`).
         */
        fun consumeInvoluntarySignOut(): Boolean = involuntarySignOut.getAndSet(false)

        /**
         * [SignOutHook]s run **before** the goodbye: telling the server the session ended revokes the token,
         * so anything needing a working credential to say goodbye (the SyncPlay group leave) must go first.
         * The goodbye itself is best effort — a failure must not strand the user in a signed-in UI.
         *
         * Server and user rows stay in Room so the next sign-in on the same server is instant. The home
         * layout cache is cleared because it is server-derived with no user id of its own; see
         * [forgetThisUsersLocalData] for the same argument applied to Room.
         *
         * The goodbye is capped at [SERVER_GOODBYE_TIMEOUT] — nothing else bounds it, and an unreachable host
         * blocks on OkHttp's own timeouts for tens of seconds. The body runs as an application-scope job this
         * call merely joins, so a caller that goes away mid-goodbye cannot cancel the sign-out between
         * revoking the token and clearing the credentials.
         */
        suspend fun signOut() {
            appScope.launch { runSignOut() }.join()
        }

        private suspend fun runSignOut() {
            val saidGoodbye =
                withTimeoutOrNull(SERVER_GOODBYE_TIMEOUT) {
                    // Both halves share the cap: a hook that hangs strands the sign-out as surely as a request
                    // that hangs, and they are talking to the same unreachable server.
                    tellTheServerGoodbye()
                }
            if (saidGoodbye == null) {
                Timber.w("The server could not be told the session ended in time; signing out regardless")
            }

            val cleared = storageCall { secureCredentialStore.clear() }
            if (cleared is AppResult.Failure) {
                Timber.e("Could not clear the stored credentials: %s", cleared.error)
            }

            homeLayoutStore.clear()
            forgetThisUsersLocalData()
            apiClientProvider.clearSession()
            sessionStateHolder.update(SessionState.LoggedOut)
            Timber.i("Signed out")
        }

        /**
         * Drops what this account left in Room that the next one must not inherit.
         *
         * A `toBeSynced` `user_data` row is **never** deleted: it is the only copy of a change the server has
         * never accepted, and losing it breaks the local-first promise that an offline change survives.
         * `items` is not keyed by user at all, so one account's cached browsing would otherwise serve the
         * next account's offline reads on a shared device; only `BROWSE_CACHE` rows go — sign-out never
         * deletes anyone's downloads.
         *
         * Deliberately **not** a [SignOutHook]: hooks need the still-valid token and share the
         * [SERVER_GOODBYE_TIMEOUT] budget, while this is local work that must finish even after an
         * unreachable host exhausted it. It runs before [SessionState.LoggedOut] is published, both so the
         * user id is still readable and so nothing can observe a half-cleared database. A failure is logged
         * and the sign-out continues.
         */
        private suspend fun forgetThisUsersLocalData() {
            val userId = (sessionStateHolder.state.value as? SessionState.LoggedIn)?.userId

            val cleared =
                storageCall {
                    userId?.let { userDataDao.deleteSynced(it) }
                    itemDao.deleteAllBrowseCache(ItemSource.BROWSE_CACHE)
                }
            when (cleared) {
                is AppResult.Success ->
                    Timber.i("Sign-out dropped %d cached item row(s)", cleared.value)
                is AppResult.Failure ->
                    Timber.e("Could not clear this user's local data: %s", cleared.error)
            }
        }

        private suspend fun tellTheServerGoodbye() {
            signOutHooks.forEach { hook ->
                try {
                    hook.onSignOut()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Throwable,
                ) {
                    Timber.w(error, "A sign-out hook failed; signing out regardless")
                }
            }

            val reported = runCatchingApi { apiFacade.reportSessionEnded() }
            if (reported is AppResult.Failure) {
                Timber.w("Could not report session end to the server: %s", reported.error)
            }
        }

        private suspend fun readStoredSession(): SessionState {
            val stored = secureCredentialStore.read()
            // Asked whatever `read` answered: a store that had to be wiped to be opened at all answers
            // `null`, which is exactly what a first run answers too.
            if (secureCredentialStore.consumeLostSession()) {
                Timber.w("The credential store destroyed a stored session it could not read")
                involuntarySignOut.set(true)
            }

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
                involuntarySignOut.set(true)
                return SessionState.LoggedOut
            }

            apiClientProvider.useSession(address, stored.accessToken)
            // The username is deliberately not logged.
            Timber.i("Restored session on '%s'", server.name)

            return SessionState.LoggedIn(
                serverId = server.id,
                userId = user.id,
                userName = user.name,
                serverName = server.name,
                serverVersion = server.version,
            )
        }

        internal companion object {
            /**
             * Shorter than any transport timeout underneath it on purpose: this is the budget for a courtesy,
             * and an unreachable server would otherwise spend OkHttp's connect *and* read timeouts (6–30s).
             */
            val SERVER_GOODBYE_TIMEOUT: Duration = 5.seconds
        }
    }
