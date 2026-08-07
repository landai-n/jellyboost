package dev.jellyboost.core.network

import dev.jellyboost.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.brandingApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BrandingOptionsDto
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.UserDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [JellyfinApiFacade] backed by the real SDK, reading the currently configured client from
 * [ApiClientProvider] on every call so that re-pointing the client takes effect immediately.
 *
 * Every call hops to [ioDispatcher]; callers therefore never need their own `withContext`.
 */
@Singleton
internal class SdkJellyfinApiFacade
    @Inject
    constructor(
        private val apiClientProvider: ApiClientProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : JellyfinApiFacade {
        private val api: ApiClient get() = apiClientProvider.apiClient

        override suspend fun getAddressCandidates(input: String): List<String> =
            withContext(ioDispatcher) {
                apiClientProvider.jellyfin.discovery
                    .getAddressCandidates(input)
                    .toList()
            }

        override suspend fun getRecommendedServers(candidates: Collection<String>): List<RecommendedServerInfo> =
            withContext(ioDispatcher) {
                apiClientProvider.jellyfin.discovery
                    .getRecommendedServers(candidates)
                    .toList()
            }

        override fun discoverLocalServers(): Flow<ServerDiscoveryInfo> =
            apiClientProvider.jellyfin.discovery
                .discoverLocalServers()
                .flowOn(ioDispatcher)

        override suspend fun getPublicUsers(): List<UserDto> =
            withContext(ioDispatcher) {
                api.userApi.getPublicUsers().content
            }

        override suspend fun getBrandingOptions(): BrandingOptionsDto =
            withContext(ioDispatcher) {
                api.brandingApi.getBrandingOptions().content
            }

        override suspend fun getQuickConnectEnabled(): Boolean =
            withContext(ioDispatcher) {
                api.quickConnectApi.getQuickConnectEnabled().content
            }

        override suspend fun authenticateUserByName(
            username: String,
            password: String,
        ): AuthenticationResult =
            withContext(ioDispatcher) {
                api.userApi
                    .authenticateUserByName(
                        AuthenticateUserByName(username = username, pw = password),
                    ).content
            }

        override suspend fun initiateQuickConnect(): QuickConnectResult =
            withContext(ioDispatcher) {
                api.quickConnectApi.initiateQuickConnect().content
            }

        override suspend fun getQuickConnectState(secret: String): QuickConnectResult =
            withContext(ioDispatcher) {
                api.quickConnectApi.getQuickConnectState(secret).content
            }

        override suspend fun authenticateWithQuickConnect(secret: String): AuthenticationResult =
            withContext(ioDispatcher) {
                api.userApi.authenticateWithQuickConnect(QuickConnectDto(secret = secret)).content
            }

        override suspend fun reportSessionEnded() {
            withContext(ioDispatcher) {
                api.sessionApi.reportSessionEnded()
            }
        }
    }
