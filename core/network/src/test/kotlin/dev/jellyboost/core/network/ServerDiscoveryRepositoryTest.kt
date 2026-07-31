package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.network.TestFixtures.SERVER_ID
import dev.jellyboost.core.network.TestFixtures.SERVER_NAME
import dev.jellyboost.core.network.TestFixtures.SERVER_VERSION
import dev.jellyboost.core.network.TestFixtures.publicSystemInfo
import dev.jellyboost.core.network.TestFixtures.recommendedServer
import dev.jellyboost.core.network.model.DiscoveredServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/** Unit tests for local-network discovery and manual address resolution. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerDiscoveryRepositoryTest {
    private val apiFacade = mockk<JellyfinApiFacade>()
    private val repository = ServerDiscoveryRepository(apiFacade, UnconfinedTestDispatcher())

    @Test
    @DisplayName("announcements are mapped to domain servers, dropping unparseable ids")
    fun mapsLocalAnnouncements() =
        runTest {
            every { apiFacade.discoverLocalServers() } returns
                flowOf(
                    ServerDiscoveryInfo(
                        address = "http://10.0.0.5:8096",
                        id = SERVER_ID.toString(),
                        name = SERVER_NAME,
                    ),
                    ServerDiscoveryInfo(
                        address = "http://10.0.0.9:8096",
                        id = "not-a-uuid",
                        name = "Broken",
                    ),
                )

            repository.discoverLocalServers().toList() shouldContainExactly
                listOf(
                    DiscoveredServer(
                        id = SERVER_ID,
                        name = SERVER_NAME,
                        address = "http://10.0.0.5:8096",
                    ),
                )
        }

    @Test
    @DisplayName("a resolvable address yields the winning candidate's system info")
    fun resolvesBestCandidate() =
        runTest {
            coEvery { apiFacade.getAddressCandidates("media.example.com") } returns
                listOf("https://media.example.com", "http://media.example.com")
            coEvery { apiFacade.getRecommendedServers(any()) } returns
                listOf(
                    recommendedServer("http://media.example.com", RecommendedServerInfoScore.GOOD),
                    recommendedServer("https://media.example.com", RecommendedServerInfoScore.GREAT),
                )

            val resolved = repository.resolveServerAddress("media.example.com").getOrNull()

            resolved?.address shouldBe "https://media.example.com"
            resolved?.serverId shouldBe SERVER_ID
            resolved?.name shouldBe SERVER_NAME
            resolved?.version shouldBe SERVER_VERSION
        }

    @Test
    @DisplayName("input that produces no candidates fails with an empty resolution error")
    fun noCandidatesFails() =
        runTest {
            coEvery { apiFacade.getAddressCandidates("   ") } returns emptyList()

            val result = repository.resolveServerAddress("   ")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error shouldBe AppError.ServerResolution()
        }

    @Test
    @DisplayName("a winning candidate without a usable server id is rejected")
    fun unusableServerIdFails() =
        runTest {
            coEvery { apiFacade.getAddressCandidates(any()) } returns listOf("https://media.example.com")
            coEvery { apiFacade.getRecommendedServers(any()) } returns
                listOf(
                    recommendedServer(
                        address = "https://media.example.com",
                        score = RecommendedServerInfoScore.GREAT,
                        systemInfo = Result.success(publicSystemInfo(id = "not-a-uuid")),
                    ),
                )

            val result = repository.resolveServerAddress("media.example.com")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.Server>()
        }

    @Test
    @DisplayName("a transport failure while probing is surfaced as a network error")
    fun transportFailureIsMapped() =
        runTest {
            coEvery { apiFacade.getAddressCandidates(any()) } returns listOf("https://media.example.com")
            coEvery { apiFacade.getRecommendedServers(any()) } throws IOException("offline")

            val result = repository.resolveServerAddress("media.example.com")

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.Network>()
        }
}
