package dev.jellyboost.core.network

import dev.jellyboost.core.network.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Splitting the state out of [AuthRepository] and [SessionRepository] is what keeps them from having to
 * inject each other — Hilt would reject that cycle.
 */
@Singleton
class SessionStateHolder
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow<SessionState>(SessionState.Unknown)

        val state: StateFlow<SessionState> = mutableState.asStateFlow()

        /** Module-internal: only the two repositories may write. */
        internal fun update(newState: SessionState) {
            mutableState.value = newState
        }
    }
