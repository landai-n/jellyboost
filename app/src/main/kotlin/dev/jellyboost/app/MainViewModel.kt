package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
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
    }
