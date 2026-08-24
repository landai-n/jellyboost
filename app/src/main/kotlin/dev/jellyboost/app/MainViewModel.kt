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
 * Restore runs once, here, because it must answer before the first frame chooses between the auth
 * flow and the signed-in graph — the splash screen is held until [sessionState] leaves
 * [SessionState.Unknown].
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
    ) : ViewModel() {
        val sessionState: StateFlow<SessionState> = sessionRepository.sessionState

        init {
            viewModelScope.launch { sessionRepository.restoreSession() }
        }
    }
