package dev.jellyboost.core.network

import app.cash.turbine.test
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.ServerAddressEntity
import dev.jellyboost.core.database.entities.ServerEntity
import dev.jellyboost.core.database.entities.UserEntity
import dev.jellyboost.core.datastore.HomeLayoutStore
import dev.jellyboost.core.datastore.SecureCredentialStore
import dev.jellyboost.core.datastore.StoredSession
import dev.jellyboost.core.network.TestFixtures.ACCESS_TOKEN
import dev.jellyboost.core.network.TestFixtures.SERVER_ADDRESS
import dev.jellyboost.core.network.TestFixtures.SERVER_ID
import dev.jellyboost.core.network.TestFixtures.SERVER_NAME
import dev.jellyboost.core.network.TestFixtures.SERVER_VERSION
import dev.jellyboost.core.network.TestFixtures.USER_ID
import dev.jellyboost.core.network.TestFixtures.USER_NAME
import dev.jellyboost.core.network.model.SessionState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/** Unit tests for offline session restore and sign-out. */
class SessionRepositoryTest {
    private val apiFacade = mockk<JellyfinApiFacade>(relaxed = true)
    private val apiClientProvider = mockk<ApiClientProvider>(relaxed = true)
    private val serverDao = mockk<ServerDao>(relaxed = true)
    private val userDao = mockk<UserDao>(relaxed = true)
    private val userDataDao = mockk<UserDataDao>(relaxed = true)
    private val itemDao = mockk<ItemDao>(relaxed = true)
    private val secureCredentialStore = mockk<SecureCredentialStore>(relaxed = true)
    private val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
    private val sessionStateHolder = SessionStateHolder()
    private val signOutHooks = linkedSetOf<SignOutHook>()

    private val storedSession =
        StoredSession(serverId = SERVER_ID, userId = USER_ID, accessToken = ACCESS_TOKEN)

    /**
     * The repository under test, built per test rather than in a `@BeforeEach` because it now needs
     * a scope, and the only scope on the test's clock is the one `runTest` is holding.
     *
     * That scope stands in for `@ApplicationScope`: a plain [SupervisorJob] on the test scheduler,
     * belonging to no coroutine, which is what the real one is — nothing cancels it short of the
     * process ending (`NetworkModule.provideApplicationScope`). Deliberately *not* `runTest`'s
     * `backgroundScope`: work launched there is invisible to [advanceUntilIdle], which drains
     * foreground tasks only, and a sign-out that outlives its caller is precisely what these tests
     * have to be able to step through.
     */
    private fun TestScope.repository() =
        SessionRepository(
            apiFacade = apiFacade,
            apiClientProvider = apiClientProvider,
            serverDao = serverDao,
            userDao = userDao,
            userDataDao = userDataDao,
            itemDao = itemDao,
            secureCredentialStore = secureCredentialStore,
            sessionStateHolder = sessionStateHolder,
            homeLayoutStore = homeLayoutStore,
            signOutHooks = signOutHooks,
            appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        )

    private fun givenCompleteDatabaseRows() {
        every { secureCredentialStore.consumeLostSession() } returns false
        coEvery { serverDao.getServer(SERVER_ID) } returns
            ServerEntity(id = SERVER_ID, name = SERVER_NAME, version = SERVER_VERSION)
        coEvery { serverDao.getAddresses(SERVER_ID) } returns
            listOf(ServerAddressEntity(id = 1, serverId = SERVER_ID, address = SERVER_ADDRESS))
        coEvery { userDao.getUser(USER_ID) } returns
            UserEntity(id = USER_ID, serverId = SERVER_ID, name = USER_NAME, primaryImageTag = null)
    }

    @Test
    @DisplayName("session state starts Unknown so the splash screen can wait for the restore")
    fun startsUnknown() =
        runTest {
            repository().sessionState.value shouldBe SessionState.Unknown
        }

    @Test
    @DisplayName("a stored session with matching rows restores as LoggedIn, without any network call")
    fun restoresStoredSession() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } returns storedSession
            givenCompleteDatabaseRows()

            repository.sessionState.test {
                awaitItem() shouldBe SessionState.Unknown

                repository.restoreSession()

                awaitItem() shouldBe
                    SessionState.LoggedIn(
                        serverId = SERVER_ID,
                        userId = USER_ID,
                        userName = USER_NAME,
                        serverName = SERVER_NAME,
                        serverVersion = SERVER_VERSION,
                    )
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { apiClientProvider.useSession(SERVER_ADDRESS, ACCESS_TOKEN) }
            coVerify(exactly = 0) { secureCredentialStore.clear() }
        }

    @Test
    @DisplayName("an empty credential store restores as LoggedOut and touches nothing")
    fun restoreWithoutStoredSession() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } returns null

            repository.restoreSession()

            repository.sessionState.value shouldBe SessionState.LoggedOut
            coVerify(exactly = 0) { serverDao.getServer(any()) }
            coVerify(exactly = 0) { apiClientProvider.useSession(any(), any()) }
        }

    @Test
    @DisplayName("a stored token whose database rows are gone is discarded")
    fun restoreWithMissingDatabaseRows() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } returns storedSession
            coEvery { serverDao.getServer(SERVER_ID) } returns null
            coEvery { serverDao.getAddresses(SERVER_ID) } returns emptyList()
            coEvery { userDao.getUser(USER_ID) } returns null

            repository.restoreSession()

            coVerify(exactly = 1) { secureCredentialStore.clear() }
            coVerify(exactly = 0) { apiClientProvider.useSession(any(), any()) }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    @Test
    @DisplayName("a stored session with a user row but no address is discarded")
    fun restoreWithMissingAddress() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } returns storedSession
            coEvery { serverDao.getServer(SERVER_ID) } returns
                ServerEntity(id = SERVER_ID, name = SERVER_NAME, version = SERVER_VERSION)
            coEvery { serverDao.getAddresses(SERVER_ID) } returns emptyList()
            coEvery { userDao.getUser(USER_ID) } returns
                UserEntity(id = USER_ID, serverId = SERVER_ID, name = USER_NAME, primaryImageTag = null)

            repository.restoreSession()

            coVerify(exactly = 1) { secureCredentialStore.clear() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    // ---- involuntary session loss (audit SEC-03) ------------------------------------------------

    @Test
    @DisplayName("a first run is not reported as a session the user lost")
    fun firstRunIsNotALoss() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } returns null

            repository.restoreSession()

            repository.consumeInvoluntarySignOut() shouldBe false
        }

    @Test
    @DisplayName("a credential store that had to wipe itself is reported as an involuntary sign-out")
    fun wipedStoreIsAnInvoluntarySignOut() =
        runTest {
            val repository = repository()
            // The store answers `null` after recreating an undecryptable file, which is exactly
            // what a first run answers — so it says separately that it destroyed something.
            coEvery { secureCredentialStore.read() } returns null
            every { secureCredentialStore.consumeLostSession() } returns true

            repository.restoreSession()

            repository.sessionState.value shouldBe SessionState.LoggedOut
            repository.consumeInvoluntarySignOut() shouldBe true
        }

    @Test
    @DisplayName("a transient storage failure signs this run out and says so")
    fun transientFailureIsAnInvoluntarySignOut() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } throws IOException("volume busy")

            repository.restoreSession()

            repository.sessionState.value shouldBe SessionState.LoggedOut
            repository.consumeInvoluntarySignOut() shouldBe true
            // The stored session is *not* the casualty of a busy disk.
            coVerify(exactly = 0) { secureCredentialStore.clear() }
        }

    @Test
    @DisplayName("a stored token whose rows are gone is reported as an involuntary sign-out")
    fun missingRowsAreAnInvoluntarySignOut() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } returns storedSession
            coEvery { serverDao.getServer(SERVER_ID) } returns null

            repository.restoreSession()

            repository.consumeInvoluntarySignOut() shouldBe true
        }

    @Test
    @DisplayName("the involuntary-sign-out flag is one-shot")
    fun involuntarySignOutIsConsumed() =
        runTest {
            val repository = repository()
            coEvery { secureCredentialStore.read() } throws IOException("volume busy")

            repository.restoreSession()

            repository.consumeInvoluntarySignOut() shouldBe true
            // A rotation must not make the auth screen accuse the store a second time.
            repository.consumeInvoluntarySignOut() shouldBe false
        }

    @Test
    @DisplayName("signing out is not an involuntary sign-out")
    fun signOutIsVoluntary() =
        runTest {
            val repository = repository()
            repository.signOut()

            repository.consumeInvoluntarySignOut() shouldBe false
        }

    @Test
    @DisplayName("signing out clears the credential store and the API client")
    fun signOutClearsEverything() =
        runTest {
            val repository = repository()
            repository.signOut()

            coVerify(exactly = 1) { apiFacade.reportSessionEnded() }
            coVerify(exactly = 1) { secureCredentialStore.clear() }
            coVerify(exactly = 1) { apiClientProvider.clearSession() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    @Test
    @DisplayName("signing out clears the home layout cache, so the next user cannot see the last one's (audit ARCH-12)")
    fun signOutClearsTheHomeLayoutCache() =
        runTest {
            val repository = repository()
            repository.signOut()

            verify(exactly = 1) { homeLayoutStore.clear() }
        }

    @Test
    @DisplayName("signing out still completes when the server cannot be told about it")
    fun signOutSurvivesServerFailure() =
        runTest {
            val repository = repository()
            coEvery { apiFacade.reportSessionEnded() } throws IOException("offline")

            repository.signOut()

            coVerify(exactly = 1) { secureCredentialStore.clear() }
            coVerify(exactly = 1) { apiClientProvider.clearSession() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    // ---- sign-out clears this account's local data (audit HYG-2) --------------------------------

    /** Signs in for real first, because the cleanup is keyed on the session that is still current. */
    private suspend fun SessionRepository.signInThenOut() {
        coEvery { secureCredentialStore.read() } returns storedSession
        givenCompleteDatabaseRows()
        restoreSession()
        signOut()
    }

    @Test
    @DisplayName("signing out drops this user's synced user-data rows")
    fun signOutDropsSyncedUserData() =
        runTest {
            repository().signInThenOut()

            coVerify(exactly = 1) { userDataDao.deleteSynced(USER_ID) }
        }

    @Test
    @DisplayName("signing out keeps the rows the server has never accepted, so they can still be pushed")
    fun signOutKeepsPendingUserData() =
        runTest {
            repository().signInThenOut()

            // `deleteSynced` is the whole guarantee: a `toBeSynced` row is the only copy of a change
            // made offline, and docs/PLAN.md's local-first story promises it is not lost. Signing
            // out on a train and back in at home must still push it. Nothing may delete the table
            // wholesale.
            coVerify(exactly = 0) { userDataDao.getPendingSync() }
            coVerify(exactly = 1) { userDataDao.deleteSynced(any()) }
        }

    @Test
    @DisplayName("signing out wipes the browse cache, so the next account cannot read it")
    fun signOutWipesTheBrowseCache() =
        runTest {
            repository().signInThenOut()

            // The `items` table is keyed by item, not by user: on a shared tablet the previous
            // account's cached browsing would otherwise serve the next one's offline read path.
            coVerify(exactly = 1) { itemDao.deleteAllBrowseCache(ItemSource.BROWSE_CACHE) }
        }

    @Test
    @DisplayName("signing out never touches a downloaded item's row")
    fun signOutKeepsDownloads() =
        runTest {
            repository().signInThenOut()

            // Deleting one would orphan the files on disk. Removing downloads is a separate,
            // explicit choice on the sign-out screen (docs/PLAN.md, "Settings").
            coVerify(exactly = 0) { itemDao.deleteDownloadsNotIn(any(), any()) }
            coVerify(exactly = 0) { itemDao.deleteAllBrowseCache(ItemSource.DOWNLOAD) }
        }

    @Test
    @DisplayName("the local data is gone before anything sees the LoggedOut state")
    fun cleanupHappensBeforeLoggedOut() =
        runTest {
            val repository = repository()
            var stateDuringCleanup: SessionState? = null
            coEvery { itemDao.deleteAllBrowseCache(any()) } coAnswers {
                stateDuringCleanup = repository.sessionState.value
                0
            }

            repository.signInThenOut()

            // Still LoggedIn while it runs — which is also what supplies the user id, and what stops
            // an observer of the transition from reading a half-cleared database.
            (stateDuringCleanup is SessionState.LoggedIn) shouldBe true
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    @Test
    @DisplayName("a database that cannot be cleared still signs the user out")
    fun signOutSurvivesCleanupFailure() =
        runTest {
            val repository = repository()
            coEvery { itemDao.deleteAllBrowseCache(any()) } throws IOException("volume busy")

            repository.signInThenOut()

            coVerify(exactly = 1) { apiClientProvider.clearSession() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    @Test
    @DisplayName("signing out with no session still wipes the shared browse cache")
    fun signOutWithoutSessionStillWipesTheCache() =
        runTest {
            // Nothing restored: there is no user id to scope `user_data` by, but the item cache is
            // not user-scoped in the first place and must still go.
            repository().signOut()

            coVerify(exactly = 1) { itemDao.deleteAllBrowseCache(ItemSource.BROWSE_CACHE) }
            coVerify(exactly = 0) { userDataDao.deleteSynced(any()) }
        }

    // ---- pre-revocation hooks (audit NET-03) ----------------------------------------------------

    @Test
    @DisplayName("sign-out hooks run before the server revokes the token, so their requests can still authenticate")
    fun signOutHooksRunBeforeRevocation() =
        runTest {
            val repository = repository()
            val hook = mockk<SignOutHook>()
            coEvery { hook.onSignOut() } returns Unit
            signOutHooks += hook

            repository.signOut()

            coVerifyOrder {
                hook.onSignOut()
                apiFacade.reportSessionEnded()
            }
        }

    @Test
    @DisplayName("a failing sign-out hook never blocks the sign-out itself")
    fun signOutSurvivesHookFailure() =
        runTest {
            val repository = repository()
            val hook = SignOutHook { throw IOException("group leave failed") }
            signOutHooks += hook

            repository.signOut()

            coVerify(exactly = 1) { apiFacade.reportSessionEnded() }
            coVerify(exactly = 1) { secureCredentialStore.clear() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    // ---- a sign-out the caller cannot lose ------------------------------------------------------

    @Test
    @DisplayName("a caller that goes away mid-goodbye does not take the sign-out with it")
    fun signOutSurvivesCallerCancellation() =
        runTest {
            val repository = repository()
            val goodbyeStarted = CompletableDeferred<Unit>()
            val serverAnswers = CompletableDeferred<Unit>()
            coEvery { apiFacade.reportSessionEnded() } coAnswers {
                goodbyeStarted.complete(Unit)
                serverAnswers.await()
            }

            // The Settings screen asking, and being popped while the request is still in flight.
            val caller = launch { repository.signOut() }
            goodbyeStarted.await()
            caller.cancel()
            serverAnswers.complete(Unit)
            advanceUntilIdle()

            // Cancelling the *caller* used to cancel the teardown between revoking the token and
            // clearing the credentials, leaving the user signed in against a dead session.
            coVerify(exactly = 1) { secureCredentialStore.clear() }
            coVerify(exactly = 1) { apiClientProvider.clearSession() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    @Test
    @DisplayName("a server that never answers costs the sign-out the goodbye timeout, not the session")
    fun signOutGivesUpOnAnUnreachableServer() =
        runTest {
            val repository = repository()
            // An unreachable host: the request neither answers nor fails, it just hangs.
            coEvery { apiFacade.reportSessionEnded() } coAnswers { awaitCancellation() }

            val startedAt = currentTime
            repository.signOut()

            currentTime - startedAt shouldBe SessionRepository.SERVER_GOODBYE_TIMEOUT.inWholeMilliseconds
            coVerify(exactly = 1) { secureCredentialStore.clear() }
            coVerify(exactly = 1) { apiClientProvider.clearSession() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }

    @Test
    @DisplayName("a sign-out hook that hangs is cut short rather than allowed to strand the user")
    fun signOutGivesUpOnAHangingHook() =
        runTest {
            val repository = repository()
            // The SyncPlay group leave against the same unreachable server (audit NET-03/SP-10).
            signOutHooks += SignOutHook { awaitCancellation() }

            repository.signOut()

            currentTime shouldBe SessionRepository.SERVER_GOODBYE_TIMEOUT.inWholeMilliseconds
            coVerify(exactly = 1) { secureCredentialStore.clear() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }
}
