package dev.jellyfinnative.core.network

import app.cash.turbine.test
import dev.jellyfinnative.core.database.dao.ServerDao
import dev.jellyfinnative.core.database.dao.UserDao
import dev.jellyfinnative.core.database.entities.ServerAddressEntity
import dev.jellyfinnative.core.database.entities.ServerEntity
import dev.jellyfinnative.core.database.entities.UserEntity
import dev.jellyfinnative.core.datastore.HomeLayoutStore
import dev.jellyfinnative.core.datastore.SecureCredentialStore
import dev.jellyfinnative.core.datastore.StoredSession
import dev.jellyfinnative.core.network.TestFixtures.ACCESS_TOKEN
import dev.jellyfinnative.core.network.TestFixtures.SERVER_ADDRESS
import dev.jellyfinnative.core.network.TestFixtures.SERVER_ID
import dev.jellyfinnative.core.network.TestFixtures.SERVER_NAME
import dev.jellyfinnative.core.network.TestFixtures.SERVER_VERSION
import dev.jellyfinnative.core.network.TestFixtures.USER_ID
import dev.jellyfinnative.core.network.TestFixtures.USER_NAME
import dev.jellyfinnative.core.network.model.SessionState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/** Unit tests for offline session restore and sign-out. */
class SessionRepositoryTest {
    private val apiFacade = mockk<JellyfinApiFacade>(relaxed = true)
    private val apiClientProvider = mockk<ApiClientProvider>(relaxed = true)
    private val serverDao = mockk<ServerDao>(relaxed = true)
    private val userDao = mockk<UserDao>(relaxed = true)
    private val secureCredentialStore = mockk<SecureCredentialStore>(relaxed = true)
    private val homeLayoutStore = mockk<HomeLayoutStore>(relaxed = true)
    private val sessionStateHolder = SessionStateHolder()

    private lateinit var repository: SessionRepository

    private val storedSession =
        StoredSession(serverId = SERVER_ID, userId = USER_ID, accessToken = ACCESS_TOKEN)

    @BeforeEach
    fun setUp() {
        repository =
            SessionRepository(
                apiFacade = apiFacade,
                apiClientProvider = apiClientProvider,
                serverDao = serverDao,
                userDao = userDao,
                secureCredentialStore = secureCredentialStore,
                sessionStateHolder = sessionStateHolder,
                homeLayoutStore = homeLayoutStore,
            )
    }

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
    fun startsUnknown() {
        repository.sessionState.value shouldBe SessionState.Unknown
    }

    @Test
    @DisplayName("a stored session with matching rows restores as LoggedIn, without any network call")
    fun restoresStoredSession() =
        runTest {
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
            coEvery { secureCredentialStore.read() } returns null

            repository.restoreSession()

            repository.consumeInvoluntarySignOut() shouldBe false
        }

    @Test
    @DisplayName("a credential store that had to wipe itself is reported as an involuntary sign-out")
    fun wipedStoreIsAnInvoluntarySignOut() =
        runTest {
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
            coEvery { secureCredentialStore.read() } returns storedSession
            coEvery { serverDao.getServer(SERVER_ID) } returns null

            repository.restoreSession()

            repository.consumeInvoluntarySignOut() shouldBe true
        }

    @Test
    @DisplayName("the involuntary-sign-out flag is one-shot")
    fun involuntarySignOutIsConsumed() =
        runTest {
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
            repository.signOut()

            repository.consumeInvoluntarySignOut() shouldBe false
        }

    @Test
    @DisplayName("signing out clears the credential store and the API client")
    fun signOutClearsEverything() =
        runTest {
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
            repository.signOut()

            verify(exactly = 1) { homeLayoutStore.clear() }
        }

    @Test
    @DisplayName("signing out still completes when the server cannot be told about it")
    fun signOutSurvivesServerFailure() =
        runTest {
            coEvery { apiFacade.reportSessionEnded() } throws IOException("offline")

            repository.signOut()

            coVerify(exactly = 1) { secureCredentialStore.clear() }
            coVerify(exactly = 1) { apiClientProvider.clearSession() }
            repository.sessionState.value shouldBe SessionState.LoggedOut
        }
}
