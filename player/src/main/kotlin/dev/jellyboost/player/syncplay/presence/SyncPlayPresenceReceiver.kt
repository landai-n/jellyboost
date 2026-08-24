package dev.jellyboost.player.syncplay.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.jellyboost.player.syncplay.SyncPlayController
import timber.log.Timber
import javax.inject.Inject

/**
 * No `goAsync()`: [SyncPlayController.leaveGroup] runs on the controller's process-lifetime scope,
 * and the presence service stays in the foreground until the controller reaches
 * [Idle][dev.jellyboost.player.syncplay.SyncPlayState.Idle], holding the process open for the trip.
 */
@AndroidEntryPoint
internal class SyncPlayPresenceReceiver : BroadcastReceiver() {
    @Inject
    lateinit var controller: SyncPlayController

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_LEAVE -> {
                Timber.i("Leaving the SyncPlay group from the notification")
                controller.leaveGroup()
            }

            else -> Timber.w("Unknown SyncPlay notification action %s", intent.action)
        }
    }

    companion object {
        const val ACTION_LEAVE = "dev.jellyboost.syncplay.LEAVE"
    }
}
