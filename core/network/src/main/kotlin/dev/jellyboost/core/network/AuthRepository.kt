package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.database.entities.ServerAddressEntity
import dev.jellyboost.core.database.entities.ServerEntity
import dev.jellyboost.core.database.entities.UserEntity
import dev.jellyboost.core.datastore.SecureCredentialStore
import dev.jellyboost.core.datastore.StoredSession
import dev.jellyboost.core.network.model.AuthenticatedSession
import dev.jellyboost.core.network.model.LoginContext
import dev.jellyboost.core.network.model.PublicUserInfo
import dev.jellyboost.core.network.model.QuickConnectSession
import dev.jellyboost.core.network.model.QuickConnectState
import dev.jellyboost.core.network.model.ResolvedServer
import dev.jellyboost.core.network.model.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Signs the user in — by password or by Quick Connect — and is the only writer of the credentials that
 * result. Every successful authentication funnels through one private path; the access token goes to
 * [SecureCredentialStore] and nowhere else, and Room only ever sees token-free rows.
 */
@Singleton
class AuthRepository
    @Suppress(
        "LongParameterList",
    )
    @Inject
    internal constructor(
        private val apiFacade: JellyfinApiFacade,
        private val apiClientProvider: ApiClientProvider,
        private val serverDao: ServerDao,
        private val userDao: UserDao,
        private val secureCredentialStore: SecureCredentialStore,
        private val sessionStateHolder: SessionStateHolder,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Only the public-user call is fatal: branding and Quick Connect availability degrade to `null`/`false`,
         * because an old or locked-down server that does not answer them must still be loggable-into.
         */
        suspend fun fetchLoginContext(server: ResolvedServer): AppResult<LoginContext> {
            apiClientProvider.useServer(server.address)

            val publicUsers =
                when (val result = runCatchingApi { apiFacade.getPublicUsers() }) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> return result
                }

            val disclaimer =
                runCatchingApi { apiFacade.getBrandingOptions() }
                    .getOrNull()
                    ?.loginDisclaimer
                    ?.takeIf { it.isNotBlank() }

            val quickConnectEnabled =
                runCatchingApi { apiFacade.getQuickConnectEnabled() }.getOrNull() ?: false

            // Host only, at debug: this is the log a user captures for a sign-in bug report. See `hostForLog`.
            Timber.d(
                "Login context for %s: %d public user(s), quickConnect=%b, disclaimer=%b",
                hostForLog(server.address),
                publicUsers.size,
                quickConnectEnabled,
                disclaimer != null,
            )

            return AppResult.Success(
                LoginContext(
                    publicUsers =
                        publicUsers.map { user ->
                            PublicUserInfo(
                                id = user.id,
                                name = user.name.orEmpty(),
                                primaryImageTag = user.primaryImageTag,
                            )
                        },
                    loginDisclaimer = disclaimer,
                    quickConnectEnabled = quickConnectEnabled,
                ),
            )
        }

        /** On success the session is fully persisted and the client authenticated; on failure nothing is written. */
        suspend fun loginWithPassword(
            server: ResolvedServer,
            username: String,
            password: String,
        ): AppResult<AuthenticatedSession> {
            apiClientProvider.useServer(server.address)
            return when (val result = runCatchingApi { apiFacade.authenticateUserByName(username, password) }) {
                is AppResult.Success -> completeAuthentication(server, result.value)
                is AppResult.Failure -> {
                    // The username is deliberately not logged: what the user typed there can be a mistyped password.
                    Timber.w("Password login failed: %s", result.error)
                    result
                }
            }
        }

        /**
         * Show [QuickConnectSession.code] to the user, then collect [observeQuickConnectState] with its
         * [QuickConnectSession.secret] and call [loginWithQuickConnect] once it reports approval.
         */
        suspend fun initiateQuickConnect(): AppResult<QuickConnectSession> =
            when (val result = runCatchingApi { apiFacade.initiateQuickConnect() }) {
                is AppResult.Success -> {
                    val secret = result.value.secret
                    val code = result.value.code
                    // The code is not logged: it authorizes this login, and logcat is a wider audience than the screen.
                    Timber.i("Quick Connect initiated")
                    AppResult.Success(QuickConnectSession(secret = secret, code = code))
                }

                is AppResult.Failure -> result
            }

        /** Emits [QuickConnectState.WaitingForApproval] per poll, then exactly one terminal value. */
        fun observeQuickConnectState(secret: String): Flow<QuickConnectState> =
            flow {
                var elapsed = Duration.ZERO
                while (elapsed < QUICK_CONNECT_TIMEOUT) {
                    when (val result = runCatchingApi { apiFacade.getQuickConnectState(secret) }) {
                        is AppResult.Success -> {
                            if (result.value.authenticated) {
                                Timber.i("Quick Connect request approved")
                                emit(QuickConnectState.Approved)
                                return@flow
                            }
                            emit(QuickConnectState.WaitingForApproval)
                        }

                        is AppResult.Failure -> {
                            val error = result.error
                            if (error is AppError.NotFound) {
                                // The server forgets a request once it expires or is denied.
                                // The 404 arrives here as `NotFound`.
                                Timber.i("Quick Connect request no longer exists on the server")
                                emit(QuickConnectState.Expired)
                            } else {
                                Timber.w("Quick Connect polling failed: %s", error)
                                emit(QuickConnectState.Failed(error))
                            }
                            return@flow
                        }
                    }

                    delay(QUICK_CONNECT_POLL_INTERVAL)
                    elapsed += QUICK_CONNECT_POLL_INTERVAL
                }

                Timber.i("Quick Connect request timed out after %s", QUICK_CONNECT_TIMEOUT)
                emit(QuickConnectState.Expired)
            }.flowOn(ioDispatcher)

        suspend fun loginWithQuickConnect(
            server: ResolvedServer,
            secret: String,
        ): AppResult<AuthenticatedSession> {
            apiClientProvider.useServer(server.address)
            return when (val result = runCatchingApi { apiFacade.authenticateWithQuickConnect(secret) }) {
                is AppResult.Success -> completeAuthentication(server, result.value)
                is AppResult.Failure -> {
                    Timber.w("Quick Connect login failed: %s", result.error)
                    result
                }
            }
        }

        /** The one place a session is turned into persisted state. Room never sees the token. */
        private suspend fun completeAuthentication(
            server: ResolvedServer,
            result: AuthenticationResult,
        ): AppResult<AuthenticatedSession> {
            val accessToken = result.accessToken
            val user = result.user
            if (accessToken.isNullOrBlank() || user == null) {
                Timber.w("Server accepted the credentials but returned no token/user")
                return AppResult.Failure(AppError.Unauthorized())
            }

            val userName = user.name.orEmpty()
            val downloadPolicyAllowed = user.policy?.enableContentDownloading ?: false

            val persisted = persistSession(server, user, userName, accessToken)
            if (persisted is AppResult.Failure) {
                Timber.e("Could not persist the new session: %s", persisted.error)
                return persisted
            }

            apiClientProvider.useSession(server.address, accessToken)
            sessionStateHolder.update(
                SessionState.LoggedIn(
                    serverId = server.serverId,
                    userId = user.id,
                    userName = userName,
                    serverName = server.name,
                    serverVersion = server.version,
                ),
            )

            // A server that forbids content downloading changes the whole offline story, so the policy is
            // surfaced loudly at every sign-in. The username is deliberately not logged.
            Timber.i(
                "Signed in on '%s' (version %s, device %s); downloads allowed by policy: %b",
                server.name,
                server.version,
                apiClientProvider.deviceId,
                downloadPolicyAllowed,
            )

            return AppResult.Success(
                AuthenticatedSession(
                    serverId = server.serverId,
                    userId = user.id,
                    userName = userName,
                    serverName = server.name,
                    serverVersion = server.version,
                    downloadPolicyAllowed = downloadPolicyAllowed,
                ),
            )
        }

        private suspend fun persistSession(
            server: ResolvedServer,
            user: UserDto,
            userName: String,
            accessToken: String,
        ): AppResult<Unit> =
            storageCall {
                serverDao.upsertServer(
                    ServerEntity(id = server.serverId, name = server.name, version = server.version),
                )
                serverDao.upsertAddresses(
                    listOf(ServerAddressEntity(serverId = server.serverId, address = server.address)),
                )
                userDao.upsertUser(
                    UserEntity(
                        id = user.id,
                        serverId = server.serverId,
                        name = userName,
                        primaryImageTag = user.primaryImageTag,
                    ),
                )
                secureCredentialStore.save(
                    StoredSession(
                        serverId = server.serverId,
                        userId = user.id,
                        accessToken = accessToken,
                    ),
                )
            }

        companion object {
            val QUICK_CONNECT_POLL_INTERVAL: Duration = 5.seconds

            val QUICK_CONNECT_TIMEOUT: Duration = 5.minutes
        }
    }
