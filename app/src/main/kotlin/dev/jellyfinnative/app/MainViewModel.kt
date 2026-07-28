package dev.jellyfinnative.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.model.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the one piece of state the whole app is keyed off: whether there is a session.
 *
 * Restore runs once, here, because it must happen before the first frame decides between the
 * auth flow and the signed-in graph — the splash screen is held until [sessionState] leaves
 * [SessionState.Unknown].
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
    ) : ViewModel() {
        /** Current session; starts at [SessionState.Unknown] until the restore has answered. */
        val sessionState: StateFlow<SessionState> = sessionRepository.sessionState

        init {
            viewModelScope.launch { sessionRepository.restoreSession() }
        }

        /**
         * Signs out and clears the stored credentials.
         *
         * Temporary home for this action: it belongs to the Settings screen (docs/PLAN.md, M9),
         * but M1's definition of done requires sign-out to be exercisable on device.
         */
        fun signOut() {
            viewModelScope.launch { sessionRepository.signOut() }
        }
    }
