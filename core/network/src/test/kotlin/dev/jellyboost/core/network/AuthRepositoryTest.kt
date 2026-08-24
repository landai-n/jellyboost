package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.database.entities.ServerAddressEntity
import dev.jellyboost.core.database.entities.ServerEntity
import dev.jellyboost.core.database.entities.UserEntity
import dev.jellyboost.core.datastore.SecureCredentialStore
import dev.jellyboost.core.datastore.StoredSession
import dev.jellyboost.core.network.TestFixtures.ACCESS_TOKEN
import dev.jellyboost.core.network.TestFixtures.SERVER_ADDRESS
import dev.jellyboost.core.network.TestFixtures.SERVER_ID
import dev.jellyboost.core.network.TestFixtures.SERVER_NAME
import dev.jellyboost.core.network.TestFixtures.SERVER_VERSION
import dev.jellyboost.core.network.TestFixtures.USER_ID
import dev.jellyboost.core.network.TestFixtures.USER_NAME
import dev.jellyboost.core.network.TestFixtures.authenticationResult
import dev.jellyboost.core.network.TestFixtures.resolvedServer
import dev.jellyboost.core.network.TestFixtures.userDto
import dev.jellyboost.core.network.model.SessionState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {
    private val apiFacade = mockk<JellyfinApiFacade>()
    private val apiClientProvider = mockk<ApiClientProvider>(relaxed = true)
    private val serverDao = mockk<ServerDao>(relaxed = true)
    private val userDao = mockk<UserDao>(relaxed = true)
    private val secureCredentialStore = mockk<SecureCredentialStore>(relaxed = true)
    private val sessionStateHolder = SessionStateHolder()

    private lateinit var repository: AuthRepository

    @BeforeEach
    fun setUp() {
        repository =
            AuthRepository(
                apiFacade = apiFacade,
                apiClientProvider = apiClientProvider,
                serverDao = serverDao,
                userDao = userDao,
                secureCredentialStore = secureCredentialStore,
                sessionStateHolder = sessionStateHolder,
                ioDispatcher = UnconfinedTestDispatcher(),
            )
    }

    @Test
    @DisplayName("a successful password login persists the server, its address and the user")
    fun successfulLoginPersistsRows() =
        runTest {
            coEvery { apiFacade.authenticateUserByName(USER_NAME, "hunter2") } returns authenticationResult()

            val serverSlot = slot<ServerEntity>()
            val addressSlot = slot<List<ServerAddressEntity>>()
            val userSlot = slot<UserEntity>()
            coEvery { serverDao.upsertServer(capture(serverSlot)) } returns Unit
            coEvery { serverDao.upsertAddresses(capture(addressSlot)) } returns Unit
            coEvery { userDao.upsertUser(capture(userSlot)) } returns Unit

            val result = repository.loginWithPassword(resolvedServer, USER_NAME, "hunter2")

            val session = result.getOrNull()
            session?.serverId shouldBe SERVER_ID
            session?.userId shouldBe USER_ID
            session?.userName shouldBe USER_NAME
            session?.serverName shouldBe SERVER_NAME
            session?.serverVersion shouldBe SERVER_VERSION
            session?.downloadPolicyAllowed shouldBe true

            serverSlot.captured shouldBe
                ServerEntity(id = SERVER_ID, name = SERVER_NAME, version = SERVER_VERSION)
            addressSlot.captured.single().serverId shouldBe SERVER_ID
            addressSlot.captured.single().address shouldBe SERVER_ADDRESS
            userSlot.captured shouldBe
                UserEntity(
                    id = USER_ID,
                    serverId = SERVER_ID,
                    name = USER_NAME,
                    primaryImageTag = "tag",
                )
        }

    @Test
    @DisplayName("the access token reaches SecureCredentialStore and nothing else")
    fun tokenOnlyGoesToSecureStore() =
        runTest {
            coEvery { apiFacade.authenticateUserByName(USER_NAME, "hunter2") } returns authenticationResult()

            val serverSlot = slot<ServerEntity>()
            val addressSlot = slot<List<ServerAddressEntity>>()
            val userSlot = slot<UserEntity>()
            val storedSlot = slot<StoredSession>()
            coEvery { serverDao.upsertServer(capture(serverSlot)) } returns Unit
            coEvery { serverDao.upsertAddresses(capture(addressSlot)) } returns Unit
            coEvery { userDao.upsertUser(capture(userSlot)) } returns Unit
            coEvery { secureCredentialStore.save(capture(storedSlot)) } returns Unit

            repository.loginWithPassword(resolvedServer, USER_NAME, "hunter2")

            storedSlot.captured shouldBe
                StoredSession(serverId = SERVER_ID, userId = USER_ID, accessToken = ACCESS_TOKEN)

            // Nothing that is written to Room may carry the token, in any field.
            serverSlot.captured.toString() shouldNotContain ACCESS_TOKEN
            addressSlot.captured.toString() shouldNotContain ACCESS_TOKEN
            userSlot.captured.toString() shouldNotContain ACCESS_TOKEN
        }

    @Test
    @DisplayName("a successful login authenticates the API client and publishes LoggedIn")
    fun successfulLoginUpdatesSessionState() =
        runTest {
            coEvery { apiFacade.authenticateUserByName(USER_NAME, "hunter2") } returns authenticationResult()

            repository.loginWithPassword(resolvedServer, USER_NAME, "hunter2")

            coVerify(exactly = 1) { apiClientProvider.useSession(SERVER_ADDRESS, ACCESS_TOKEN) }
            sessionStateHolder.state.value shouldBe
                SessionState.LoggedIn(
                    serverId = SERVER_ID,
                    userId = USER_ID,
                    userName = USER_NAME,
                    serverName = SERVER_NAME,
                    serverVersion = SERVER_VERSION,
                )
        }

    @Test
    @DisplayName("a server that forbids downloads is reported on the session")
    fun downloadPolicyIsCarriedThrough() =
        runTest {
            coEvery { apiFacade.authenticateUserByName(USER_NAME, "hunter2") } returns
                authenticationResult(user = userDto(downloadPolicyAllowed = false))

            val result = repository.loginWithPassword(resolvedServer, USER_NAME, "hunter2")

            result.getOrNull()?.downloadPolicyAllowed shouldBe false
        }

    @Test
    @DisplayName("rejected credentials fail with Unauthorized and persist nothing")
    fun rejectedCredentialsPersistNothing() =
        runTest {
            coEvery { apiFacade.authenticateUserByName(USER_NAME, "wrong") } throws InvalidStatusException(401)

            val result = repository.loginWithPassword(resolvedServer, USER_NAME, "wrong")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.Unauthorized>()

            coVerify(exactly = 0) { serverDao.upsertServer(any()) }
            coVerify(exactly = 0) { serverDao.upsertAddresses(any()) }
            coVerify(exactly = 0) { userDao.upsertUser(any()) }
            coVerify(exactly = 0) { secureCredentialStore.save(any()) }
            coVerify(exactly = 0) { apiClientProvider.useSession(any(), any()) }
            sessionStateHolder.state.value shouldBe SessionState.Unknown
        }

    @Test
    @DisplayName("an authentication response without a token is treated as unauthorized")
    fun missingTokenIsUnauthorized() =
        runTest {
            coEvery { apiFacade.authenticateUserByName(USER_NAME, "hunter2") } returns
                authenticationResult(accessToken = null)

            val result = repository.loginWithPassword(resolvedServer, USER_NAME, "hunter2")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.Unauthorized>()
            coVerify(exactly = 0) { secureCredentialStore.save(any()) }
        }

    @Test
    @DisplayName("Quick Connect sign-in persists exactly like a password sign-in")
    fun quickConnectLoginPersistsSession() =
        runTest {
            coEvery { apiFacade.authenticateWithQuickConnect("secret") } returns authenticationResult()
            val storedSlot = slot<StoredSession>()
            coEvery { secureCredentialStore.save(capture(storedSlot)) } returns Unit

            val result = repository.loginWithQuickConnect(resolvedServer, "secret")

            result.getOrNull()?.userId shouldBe USER_ID
            storedSlot.captured.accessToken shouldBe ACCESS_TOKEN
            sessionStateHolder.state.value.shouldBeInstanceOf<SessionState.LoggedIn>()
        }
}
