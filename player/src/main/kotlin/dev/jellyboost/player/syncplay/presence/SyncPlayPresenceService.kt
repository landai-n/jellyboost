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
 * The foreground service a SyncPlay group runs behind when nothing is playing.
 *
 * It does no work. Its entire purpose is to be a foreground service, because that is the only thing
 * Android offers that keeps a backgrounded process's network alive — and on the test tablet
 * (the OEM ROM, Android 16) a backgrounded app with no foreground service loses its network within
 * about forty seconds, which the controller correctly reads as a lost connection and which costs the
 * user their group (STATUS.md; DECISIONS.md 2026-07-31). The user's own case is precisely this one:
 * this app in a group on one half of the tablet, jellyfin-web driving it on the other.
 *
 * [PlaybackService][dev.jellyboost.player.session.PlaybackService] already does the same job
 * while something is playing, so the two never run together — see [syncPlayPresenceDemanded].
 *
 * ### `specialUse`
 * Holding a real-time group-membership session is not `mediaPlayback` (nothing is playing),
 * `connectedDevice` (the peer is a server over the ordinary network, and the type demands
 * permissions this app has no business holding) or `dataSync` (deprecated in Android 15 and capped
 * at six hours a day on targetSdk 35+). `specialUse` is the honest answer, and the manifest carries
 * the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration that API 34+ asks for. The network exemption is
 * identical whichever type is declared — it comes from being in the foreground at all.
 *
 * `START_NOT_STICKY`, and a `null` intent stops the service outright: after a process death the
 * `SyncPlayController` singleton and its membership are gone, so a resurrected notification would
 * promise a group that no longer exists.
 */
internal class SyncPlayPresenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Promotes in `onCreate`, not in `onStartCommand` — and this is not a style choice.
     *
     * `startForegroundService` opens a deadline that is only closed by `startForeground`, and a
     * `stopService` arriving *first* does not close it: the platform kills the process with
     * `ForegroundServiceDidNotStartInTimeException`. That is not hypothetical — it happened on the
     * device, when a foreground re-check found its group dissolved a quarter of a second after
     * asking for it and the demand went up and straight back down (2026-07-31 device session).
     * `onCreate` always runs, and always before `onDestroy`, so promoting here means the deadline is
     * met however quickly the demand is withdrawn.
     */
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // The promotion itself can be refused: API 31+ throws
        // `ForegroundServiceStartNotAllowedException` when the process slipped to the background
        // between `startForegroundService` and here, and API 34+ for a type/permission mismatch.
        // Uncaught, either one kills the whole process for a notification (audit SP-17) — the
        // group costs at most itself, so the service stands down instead.
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
     * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` exists only from API 34.
     *
     * Below it the type is left unset rather than guessed at: pre-34 platforms do not parse
     * `specialUse` out of the manifest, and a `startForeground` naming a type the manifest did not
     * declare is refused. An untyped promotion is exactly as good for the one thing this service
     * needs, which is to be in the foreground.
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    /** Creates the channel; safe to call repeatedly, and minSdk is 26 so it always applies. */
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

    /**
     * "In a SyncPlay group — waiting for the group", with the one action that matters.
     *
     * Leaving is offered on the notification because this is the state where the app is *not* on
     * screen: a user who no longer wants to be in the group should not have to go and find it. The
     * body tapped opens the app, which is where the group's own screen is.
     */
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
