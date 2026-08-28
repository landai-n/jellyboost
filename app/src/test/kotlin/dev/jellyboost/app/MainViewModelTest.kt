package dev.jellyboost.app

import app.cash.turbine.test
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.core.datastore.AppPreferences
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val sessionFlow = MutableStateFlow<SessionState>(SessionState.Unknown)
    private val sessionRepository = mockk<SessionRepository>(relaxed = true)
    private val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private val dynamicColorEnabled = MutableStateFlow(false)
    private val appPreferences = mockk<AppPreferences>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { sessionRepository.sessionState } returns sessionFlow
        every { appPreferences.themeMode } returns themeMode
        every { appPreferences.dynamicColorEnabled } returns dynamicColorEnabled
    }

    @Test
    @DisplayName("session restore is kicked off exactly once, when the ViewModel is created")
    fun restoresTheSessionOnce() =
        runTest {
            val model = viewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { sessionRepository.restoreSession() }
            model.sessionState.value shouldBe SessionState.Unknown
        }

    @Test
    @DisplayName("the repository's session state is passed straight through")
    fun passesSessionStateThrough() =
        runTest {
            val model = viewModel()
            advanceUntilIdle()

            model.sessionState.test {
                awaitItem() shouldBe SessionState.Unknown

                sessionFlow.value = LOGGED_IN
                awaitItem() shouldBe LOGGED_IN

                sessionFlow.value = SessionState.LoggedOut
                awaitItem() shouldBe SessionState.LoggedOut
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("the theme preference starts at the store's own default, then follows it")
    fun exposesTheThemePreference() =
        runTest {
            val model = viewModel()
            advanceUntilIdle()

            model.themePreference.test {
                awaitItem() shouldBe ThemePreference(mode = ThemeMode.SYSTEM, dynamicColor = false)

                themeMode.value = ThemeMode.LIGHT
                awaitItem() shouldBe ThemePreference(mode = ThemeMode.LIGHT, dynamicColor = false)

                dynamicColorEnabled.value = true
                awaitItem() shouldBe ThemePreference(mode = ThemeMode.LIGHT, dynamicColor = true)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // Sign-out belongs to `:feature:settings`; `SettingsViewModelTest` covers it.

    private fun viewModel() = MainViewModel(sessionRepository, appPreferences)

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
