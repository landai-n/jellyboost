package dev.jellyboost.player.syncplay.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.jellyboost.player.syncplay.SyncPlayController
import timber.log.Timber
import javax.inject.Inject

/**
 * Backs the **Leave** action on the SyncPlay group notification.
 *
 * A receiver rather than an activity, for the same reason the downloads notification uses one: the
 * whole point of the notification is that the group is controllable from wherever the user happens
 * to be, and leaving must not drag the app to the front to do it.
 *
 * No `goAsync()` is needed. [SyncPlayController.leaveGroup] is fire-and-forget on the controller's
 * process-lifetime scope, and the presence service is still in the foreground at this moment — it
 * is stopped by the coordinator only once the controller has reached
 * [Idle][dev.jellyboost.player.syncplay.SyncPlayState.Idle] — so the process is held open for
 * the round trip.
 */
@AndroidEntryPoint
class SyncPlayPresenceReceiver : BroadcastReceiver() {
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
        /** Leaves the group, deliberately — so nothing will try to take it back. */
        const val ACTION_LEAVE = "dev.jellyboost.syncplay.LEAVE"
    }
}
