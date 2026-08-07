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
 * Keeps a group alive across the app being backgrounded — the two halves of DECISIONS.md
 * 2026-07-31.
 *
 * 1. **The service.** While the controller is in a group and nothing is playing, it holds
 *    [SyncPlayPresenceService] so the process keeps its network. [syncPlayPresenceDemanded] is the
 *    whole rule and is unit-tested on its own.
 * 2. **The foreground re-check.** Returning to the app is the one moment a membership lost to an
 *    OEM network cut can actually be taken back, so `ON_START` hands over to
 *    [SyncPlayController.onAppForegrounded] — which pings immediately if the group is still held,
 *    and quietly re-joins a recently, involuntarily lost one if it is not.
 *
 * Started from `JellyboostApplication.onCreate`, the seam the app already uses for work that
 * must run whether or not a screen is showing (`UserDataSyncTrigger`, `DownloadedMetadataRefresher`).
 * `ProcessLifecycleOwner` rather than a Compose lifecycle hook because the state being recovered
 * exists while no screen is composed at all.
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
                    // Settled demand only. A rejoin that fails in a quarter of a second, or a player
                    // that hands over to another, would otherwise post an ongoing notification the
                    // user sees flash and vanish — and the platform charges real money for a
                    // foreground service withdrawn that fast (see SyncPlayPresenceService.onCreate).
                    .debounce(DEMAND_SETTLE_MS)
                    .distinctUntilChanged()
                    .collect { demanded -> if (demanded) startPresenceService() else stopPresenceService() }
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            controller.onAppForegrounded()
            // A demand raised while the app was in the background may never have been met: Android
            // refuses a foreground start from there, and the OEM may have killed the service anyway.
            // The app is on screen now, so this start is allowed and costs nothing if it is a repeat.
            if (syncPlayPresenceDemanded(controller.state.value, playbackServiceState.running.value)) {
                startPresenceService()
            }
        }

        /**
         * Best effort, exactly like `ExoPlayerHandle.startPlaybackService`.
         *
         * A foreground start from the background throws from API 31 onwards, and the demand can rise
         * there — playback ending while the app is off screen is the ordinary case. Losing this start
         * costs the network-holding bonus and nothing else; [onStart] tries again the moment the user
         * comes back, which is the only moment the group can be recovered anyway.
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
             * How long a demand has to hold before the service is started or stopped, in
             * milliseconds. Long enough to swallow a failed rejoin and a player handover, short
             * enough that pressing Home a moment after joining still finds the service up.
             */
            const val DEMAND_SETTLE_MS = 400L
        }
    }
