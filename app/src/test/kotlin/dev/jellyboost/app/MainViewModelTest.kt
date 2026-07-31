package dev.jellyboost.app

import app.cash.turbine.test
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID

/** Unit tests for the activity-level session state holder. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val sessionFlow = MutableStateFlow<SessionState>(SessionState.Unknown)
    private val sessionRepository = mockk<SessionRepository>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { sessionRepository.sessionState } returns sessionFlow
    }

    @Test
    @DisplayName("session restore is kicked off exactly once, when the ViewModel is created")
    fun restoresTheSessionOnce() =
        runTest {
            val viewModel = MainViewModel(sessionRepository)
            advanceUntilIdle()

            coVerify(exactly = 1) { sessionRepository.restoreSession() }
            viewModel.sessionState.value shouldBe SessionState.Unknown
        }

    @Test
    @DisplayName("the repository's session state is passed straight through")
    fun passesSessionStateThrough() =
        runTest {
            val viewModel = MainViewModel(sessionRepository)
            advanceUntilIdle()

            viewModel.sessionState.test {
                awaitItem() shouldBe SessionState.Unknown

                sessionFlow.value = LOGGED_IN
                awaitItem() shouldBe LOGGED_IN

                sessionFlow.value = SessionState.LoggedOut
                awaitItem() shouldBe SessionState.LoggedOut
                cancelAndIgnoreRemainingEvents()
            }
        }

    // Sign-out is no longer this ViewModel's concern: it moved into `:feature:settings`'s Account
    // section at M9, and `SettingsViewModelTest` covers it (including the delete-then-sign-out
    // ordering, which is the part worth pinning). The test that lived here went with the method.

    private companion object {
        val LOGGED_IN =
            SessionState.LoggedIn(
                serverId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                userId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                userName = "casey",
                serverName = "Living Room",
                serverVersion = "10.11.0",
            )
    }
}
