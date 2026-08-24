package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.datastore.SecureCredentialStore
import dev.jellyboost.core.network.TestFixtures.quickConnectResult
import dev.jellyboost.core.network.model.QuickConnectState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the Quick Connect polling loop, run on virtual time so the 5-second interval
 * and the 5-minute cap can be asserted exactly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryQuickConnectTest {
    private val apiFacade = mockk<JellyfinApiFacade>()

    /** Builds the repository on [TestScope]'s scheduler so `delay` is virtual. */
    private fun TestScope.repository(
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): AuthRepository =
        AuthRepository(
            apiFacade = apiFacade,
            apiClientProvider = mockk(relaxed = true),
            serverDao = mockk<ServerDao>(relaxed = true),
            userDao = mockk<UserDao>(relaxed = true),
            secureCredentialStore = mockk<SecureCredentialStore>(relaxed = true),
            sessionStateHolder = SessionStateHolder(),
            ioDispatcher = dispatcher,
        )

    @Test
    @DisplayName("initiating Quick Connect surfaces the secret and the user-facing code")
    fun initiateReturnsSecretAndCode() =
        runTest {
            coEvery { apiFacade.initiateQuickConnect() } returns
                quickConnectResult(authenticated = false, secret = "s3cr3t", code = "428913")

            val session = repository().initiateQuickConnect().getOrNull()

            session?.secret shouldBe "s3cr3t"
            session?.code shouldBe "428913"
        }

    @Test
    @DisplayName("polling emits WaitingForApproval per poll and completes on approval")
    fun approvalCompletesTheFlow() =
        runTest {
            coEvery { apiFacade.getQuickConnectState("s3cr3t") } returnsMany
                listOf(
                    quickConnectResult(authenticated = false),
                    quickConnectResult(authenticated = false),
                    quickConnectResult(authenticated = true),
                )

            val states = repository().observeQuickConnectState("s3cr3t").toList()

            states shouldContainExactly
                listOf(
                    QuickConnectState.WaitingForApproval,
                    QuickConnectState.WaitingForApproval,
                    QuickConnectState.Approved,
                )
            // Two 5-second waits happened before the approving poll.
            testScheduler.currentTime shouldBe 2 * 5.seconds.inWholeMilliseconds
        }

    @Test
    @DisplayName("polling gives up after the 5-minute cap, having polled every 5 seconds")
    fun pollingExpiresAfterFiveMinutes() =
        runTest {
            coEvery { apiFacade.getQuickConnectState("s3cr3t") } returns
                quickConnectResult(authenticated = false)

            val states = repository().observeQuickConnectState("s3cr3t").toList()

            val expectedPolls = (5.minutes / 5.seconds).toInt()
            states.size shouldBe expectedPolls + 1
            states.dropLast(1).all { it == QuickConnectState.WaitingForApproval } shouldBe true
            states.last() shouldBe QuickConnectState.Expired
            testScheduler.currentTime shouldBe 5.minutes.inWholeMilliseconds
        }

    @Test
    @DisplayName("a request the server has forgotten (404) ends the flow as expired")
    fun forgottenRequestExpires() =
        runTest {
            coEvery { apiFacade.getQuickConnectState("s3cr3t") } throws InvalidStatusException(404)

            repository().observeQuickConnectState("s3cr3t").toList() shouldContainExactly
                listOf(QuickConnectState.Expired)
        }

    @Test
    @DisplayName("a transport failure ends the flow with the mapped error")
    fun transportFailureIsReported() =
        runTest {
            coEvery { apiFacade.getQuickConnectState("s3cr3t") } throws IOException("offline")

            val states = repository().observeQuickConnectState("s3cr3t").toList()

            val failure = states.single()
            failure.shouldBeInstanceOf<QuickConnectState.Failed>()
            failure.error.shouldBeInstanceOf<AppError.Network>()
        }
}
