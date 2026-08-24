package dev.jellyboost.player.syncplay.presence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.jellyboost.player.R
import timber.log.Timber

/**
 * Does no work: being a foreground service is the whole point, since that is the only way to keep a
 * backgrounded process's network alive. MEASURED on the OEM ROM (Android 16): a backgrounded app
 * without one loses its network in about forty seconds, which the controller reads as a lost group.
 *
 * Never runs alongside [PlaybackService][dev.jellyboost.player.session.PlaybackService], which does
 * the same job while something is playing — see [syncPlayPresenceDemanded].
 *
 * `specialUse` because the alternatives do not fit: `mediaPlayback` (nothing is playing),
 * `connectedDevice` (demands permissions this app should not hold), `dataSync` (deprecated in
 * Android 15, capped at six hours a day on targetSdk 35+). The network exemption is the same for
 * any type. The manifest carries the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` API 34+ requires.
 *
 * `START_NOT_STICKY`, and a `null` intent stops the service: after a process death the membership is
 * gone, so a resurrected notification would promise a group that no longer exists.
 */
internal class SyncPlayPresenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Promotion must stay in `onCreate`, never `onStartCommand`: `startForegroundService` opens a
     * deadline that only `startForeground` closes — an earlier `stopService` does not, and the
     * platform kills the process with `ForegroundServiceDidNotStartInTimeException`.
     */
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // The promotion can be refused: API 31+ throws when the process slipped to the background
        // in between, API 34+ on a type/permission mismatch. Uncaught, either kills the process.
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundServiceType())
        }.onFailure { error ->
            Timber.w(error, "Could not promote the SyncPlay presence service; stopping it")
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent == null) {
            Timber.d("SyncPlay presence service restarted with nothing behind it; stopping")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Unset below API 34: pre-34 platforms do not parse `specialUse` from the manifest, and
     * `startForeground` naming an undeclared type is refused.
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private fun ensureChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.player_syncplay_presence_channel_name),
                // LOW: a standing membership must never buzz the device.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.player_syncplay_presence_channel_description)
                setShowBadge(false)
            }
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    /** Leaving is offered here because this is the state where the app is *not* on screen. */
    private fun buildNotification(): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fin)
            .setContentTitle(getString(R.string.player_syncplay_presence_title))
            .setContentText(getString(R.string.player_syncplay_presence_text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { launchIntent()?.let(::setContentIntent) }
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.player_syncplay_presence_leave),
                leaveIntent(),
            ).build()

    private fun leaveIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            LEAVE_REQUEST_CODE,
            Intent(this, SyncPlayPresenceReceiver::class.java).setAction(SyncPlayPresenceReceiver.ACTION_LEAVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Built from the launcher intent, like `PlaybackService`'s, so `:player` needs no `:app`. */
    private fun launchIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                CONTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    override fun onDestroy() {
        Timber.d("SyncPlay presence service stopped")
        super.onDestroy()
    }

    internal companion object {
        const val CHANNEL_ID = "syncplay"

        /** Distinct from the downloads notification's 4201; one group at a time, so one id. */
        const val NOTIFICATION_ID = 4301

        private const val LEAVE_REQUEST_CODE = 1
        private const val CONTENT_REQUEST_CODE = 2
    }
}
