package dev.jellyboost.player.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether [PlaybackService] is alive, published so things outside playback can stand aside for it.
 *
 * There is exactly one consumer and one reason for it (DECISIONS.md 2026-07-31): a SyncPlay group
 * with no playback holds its own foreground service to keep the process's network up, and that
 * service must not run alongside the media one. `PlaybackService` already holds the foreground
 * while it exists, so "is it running" is the whole question — and the platform gives no way to ask
 * it that does not involve `ActivityManager` and a string comparison on a class name.
 *
 * Written only by [PlaybackService], from `onCreate` and `onDestroy`.
 */
@Singleton
class PlaybackServiceState
    @Inject
    constructor() {
        private val _running = MutableStateFlow(false)

        /** `true` between [PlaybackService]'s `onCreate` and its `onDestroy`. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Publishes the service's own lifecycle. Called by [PlaybackService] only. */
        fun setRunning(running: Boolean) {
            _running.value = running
        }
    }
