package dev.jellyfinnative.core.network

import dev.jellyfinnative.core.network.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single mutable cell holding the current [SessionState].
 *
 * [AuthRepository] writes to it on sign-in and [SessionRepository] on restore/sign-out, while
 * the rest of the app only ever reads `SessionRepository.sessionState`. Splitting the state out
 * of both repositories is what keeps them from having to inject each other — Hilt would reject
 * that cycle.
 */
@Singleton
class SessionStateHolder
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow<SessionState>(SessionState.Unknown)

        /** Current session, starting at [SessionState.Unknown] until a restore has run. */
        val state: StateFlow<SessionState> = mutableState.asStateFlow()

        /** Publishes [newState]. Module-internal: only the two repositories may write. */
        internal fun update(newState: SessionState) {
            mutableState.value = newState
        }
    }
