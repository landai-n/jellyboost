package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the window needs to know about the colour scheme, and nothing else. */
data class ThemePreference(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
)

/**
 * Restore runs once, here, because it must answer before the first frame chooses between the auth
 * flow and the signed-in graph — the splash screen is held until [sessionState] leaves
 * [SessionState.Unknown].
 *
 * [themePreference] is here rather than in a second ViewModel of its own: `MainActivity` is already
 * this one's only host, and a competing observation path is how the window and the composition end
 * up disagreeing about which scheme is drawn.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        appPreferences: AppPreferences,
    ) : ViewModel() {
        val sessionState: StateFlow<SessionState> = sessionRepository.sessionState

        /**
         * The initial value is the store's own default, so the frames before DataStore answers are
         * the app's shipped appearance rather than a flash of the wrong one.
         */
        val themePreference: StateFlow<ThemePreference> =
            combine(
                appPreferences.themeMode,
                appPreferences.dynamicColorEnabled,
            ) { mode, dynamicColor -> ThemePreference(mode = mode, dynamicColor = dynamicColor) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                    initialValue = ThemePreference(),
                )

        init {
            viewModelScope.launch { sessionRepository.restoreSession() }
        }

        private companion object {
            /** Survives a configuration change, so rotating does not re-read DataStore for the same values. */
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
