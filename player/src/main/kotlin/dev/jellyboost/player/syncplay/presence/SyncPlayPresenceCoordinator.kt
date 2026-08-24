package dev.jellyboost.player.syncplay.presence

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.player.session.PlaybackServiceState
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.di.SyncPlayScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a SyncPlay group alive while the app is backgrounded: holds [SyncPlayPresenceService] so the
 * process keeps its network, and re-checks membership on foreground.
 *
 * `ProcessLifecycleOwner`, not a Compose lifecycle hook: the state being recovered exists while no
 * screen is composed at all.
 */
@Singleton
class SyncPlayPresenceCoordinator
    @Inject
    internal constructor(
        @ApplicationContext private val context: Context,
        private val controller: SyncPlayController,
        private val playbackServiceState: PlaybackServiceState,
        @SyncPlayScope private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private var started = false

        /** Idempotent, and must be called from the main thread — `ProcessLifecycleOwner` requires it. */
        fun start() {
            if (started) return
            started = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            scope.launch {
                combine(
                    controller.state,
                    playbackServiceState.running,
                    ::syncPlayPresenceDemanded,
                ).distinctUntilChanged()
                    // Settled demand only: the platform penalises a foreground service withdrawn
                    // moments after it starts (see SyncPlayPresenceService.onCreate).
                    .debounce(DEMAND_SETTLE_MS)
                    .distinctUntilChanged()
                    .collect { demanded -> if (demanded) startPresenceService() else stopPresenceService() }
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            controller.onAppForegrounded()
            // A demand raised in the background may never have been met (Android refuses a foreground
            // start from there); on screen the start is allowed, and a repeat costs nothing.
            if (syncPlayPresenceDemanded(controller.state.value, playbackServiceState.running.value)) {
                startPresenceService()
            }
        }

        /**
         * Best effort: a foreground start from the background throws from API 31 onwards and the
         * demand can rise there. Losing it costs only the network hold; [onStart] tries again.
         */
        private fun startPresenceService() {
            runCatching { ContextCompat.startForegroundService(context, intent()) }
                .onFailure { Timber.w(it, "Could not hold the SyncPlay presence service") }
        }

        private fun stopPresenceService() {
            runCatching { context.stopService(intent()) }
                .onFailure { Timber.w(it, "Could not release the SyncPlay presence service") }
        }

        private fun intent() = Intent(context, SyncPlayPresenceService::class.java)

        private companion object {
            /**
             * Long enough to swallow a failed rejoin or a player handover, short enough that
             * pressing Home just after joining still finds the service up.
             */
            const val DEMAND_SETTLE_MS = 400L
        }
    }
