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
 * Owns the lifecycle of an already-established session: restoring it on app start and tearing
 * it down on sign-out (docs/PLAN.md, M1).
 *
 * Restore is deliberately network-free — it reads the token from [SecureCredentialStore] and
 * the server/user details from Room — so the app comes up signed in with no connectivity at
 * all, which is what the offline story depends on.
 */
@Singleton
class SessionRepository
    @Suppress(
        // Eleven DI collaborators: sign-out has to reach every store holding user-scoped state (audit H2), and the
        // plug-in `SignOutHook` set is how other modules join that sweep.
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
         * So is the account's local footprint in Room — see [forgetThisUsersLocalData], which is the
         * same argument applied to the two tables that actually hold it.
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
            forgetThisUsersLocalData()
            apiClientProvider.clearSession()
            sessionStateHolder.update(SessionState.LoggedOut)
            Timber.i("Signed out")
        }

        /**
         * Drops what this account left in Room that the next one must not inherit (audit HYG-2).
         *
         * Two tables, for two different reasons:
         *
         * - **`user_data`** is keyed by user, and every synced row in it is pure cache — a copy of
         *   what the server already holds, worth nothing once the account is gone from the device.
         *   A `toBeSynced` row is **not** deleted: it is the only copy of a change the server has
         *   never accepted, and docs/PLAN.md's user-data story ("local-first always"; the sync
         *   worker drains pending rows when the network comes back) is a promise that a change made
         *   offline is not lost. Signing out on a train and back in at home must still push it —
         *   `UserDataDao.deleteSynced` draws exactly that line, and has said so in its own
         *   documentation since M7.
         * - **`items`** is *not* keyed by user at all — an item id belongs to the server — so one
         *   account's cached browsing would otherwise keep serving the next account's offline read
         *   path and search results on a shared tablet, including items that account cannot see.
         *   Only `BROWSE_CACHE` rows go: signing out never deletes anyone's downloads, which the
         *   plan makes a separate, explicit choice on the sign-out screen.
         *
         * Deliberately **not** a [SignOutHook]. Hooks exist for work that needs the *still-valid
         * token* and share one [SERVER_GOODBYE_TIMEOUT] budget with the server goodbye (audit
         * NET-03); this is local work that needs no server, must not spend that budget, and must
         * finish whether or not an unreachable host already exhausted it. It runs after the goodbye
         * and before [SessionState.LoggedOut] is published, so nothing observing the sign-out can
         * read a half-cleared database.
         *
         * The user id comes from the session that is still current — it is exactly why this runs
         * before the state transition. A failure here is logged and the sign-out continues: leftover
         * cache rows are a privacy and disk-space problem, and stranding the user in a signed-in UI
         * is a worse one.
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

            val reported = runCatchingApi { apiFacade.reportSessionEnded() }
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
