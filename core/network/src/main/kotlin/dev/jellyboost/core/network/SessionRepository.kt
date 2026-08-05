package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.datastore.HomeLayoutStore
import dev.jellyboost.core.datastore.SecureCredentialStore
import dev.jellyboost.core.network.di.ApplicationScope
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
        private val homeLayoutStore: HomeLayoutStore,
        private val signOutHooks: Set<@JvmSuppressWildcards SignOutHook>,
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        /**
         * Current session. Starts at [SessionState.Unknown]; hold the splash screen until it
         * becomes something else.
         */
        val sessionState: StateFlow<SessionState> = sessionStateHolder.state

        private val involuntarySignOut = AtomicBoolean(false)

        /**
         * Restores the stored session, if any, and configures the API client for it.
         *
         * A stored token whose server or user rows are gone is an inconsistent state (a wiped
         * database, a restored backup): the token is discarded and the user signs in again.
         * A transient storage failure, by contrast, leaves the stored session alone and only
         * reports [SessionState.LoggedOut] for this app run.
         *
         * Every path that signs the user out **without them asking** records it, so that the auth
         * screen can say what happened rather than presenting an unexplained sign-in form
         * (docs/notes/audit-2026-07.md, SEC-03) — see [consumeInvoluntarySignOut]. Having *no*
         * stored session is not one of those paths: that is a first run.
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
         * Whether the last restore lost a session the user never signed out of — reading it clears
         * the flag, so the message it drives is shown once.
         *
         * Read by the first auth screen the user lands on. It is a poll rather than an event stream
         * because the answer is settled before that screen can exist: the splash is held until
         * [restoreSession] has finished (`MainActivity`).
         */
        fun consumeInvoluntarySignOut(): Boolean = involuntarySignOut.getAndSet(false)

        /**
         * Signs out: runs every [SignOutHook] while the token still works (audit NET-03 — telling
         * the server the session ended revokes it, so anything that needs a working credential to
         * say goodbye, like the SyncPlay group leave, must go first), then tells the server the
         * session ended (best effort — a failure here must not strand the user in a signed-in UI),
         * wipes [SecureCredentialStore], drops the token from the API client and reports
         * [SessionState.LoggedOut].
         *
         * Server and user rows stay in Room so the next sign-in on the same server is instant;
         * the plan only requires the credential store to be cleared.
         *
         * The home layout cache is cleared too (audit ARCH-12): it is a server-derived value with
         * no user id of its own, so leaving it behind would let the next user who signs in on this
         * device see whatever the previous one's server last reported, for as long as their first
         * fetch keeps failing.
         *
         * Two things make that promise hold when the server is *unreachable* rather than merely
         * unhappy, which is the case that used to lose sign-outs entirely:
         *
         * - The goodbye is capped at [SERVER_GOODBYE_TIMEOUT]. Nothing else bounds it — an
         *   unreachable host blocks on OkHttp's own timeouts for tens of seconds — and the local
         *   teardown is what the user actually asked for.
         * - The whole body runs as a job in the application scope, which this call merely joins. A
         *   caller that goes away mid-goodbye (the Settings screen popped while the request hangs)
         *   abandons the join; the sign-out itself still runs to completion, instead of being
         *   cancelled between revoking the token and clearing the credentials and stranding the
         *   user in a signed-in UI with a dead session.
         */
        suspend fun signOut() {
            appScope.launch { runSignOut() }.join()
        }

        private suspend fun runSignOut() {
            val saidGoodbye =
                withTimeoutOrNull(SERVER_GOODBYE_TIMEOUT) {
                    // Both halves share the cap: a hook that hangs is as good a way to never sign
                    // out as a request that hangs, and they are talking to the same unreachable
                    // server (audit NET-03/SP-10 — `SyncPlayController.watchSignOut` is the net
                    // that catches the group leave a cut-short hook did not finish).
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
            apiClientProvider.clearSession()
            sessionStateHolder.update(SessionState.LoggedOut)
            Timber.i("Signed out")
        }

        /** The part of a sign-out that needs a server: the pre-revocation hooks, then the goodbye. */
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

            val reported = apiCall { apiFacade.reportSessionEnded() }
            if (reported is AppResult.Failure) {
                Timber.w("Could not report session end to the server: %s", reported.error)
            }
        }

        private suspend fun readStoredSession(): SessionState {
            val stored = secureCredentialStore.read()
            // Asked whatever `read` answered: a store that had to be wiped to be opened at all
            // answers `null`, which is exactly what a first run answers too.
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
            // The username is deliberately not logged (audit SEC-05).
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
             * How long the sign-out is willing to spend saying goodbye to the server before it
             * signs out locally anyway.
             *
             * Shorter than any transport timeout underneath it on purpose: this is the budget for a
             * courtesy, and an unreachable server would otherwise spend OkHttp's connect *and* read
             * timeouts — 6 to 30 seconds — with the user staring at a button that did nothing.
             */
            val SERVER_GOODBYE_TIMEOUT: Duration = 5.seconds
        }
    }
