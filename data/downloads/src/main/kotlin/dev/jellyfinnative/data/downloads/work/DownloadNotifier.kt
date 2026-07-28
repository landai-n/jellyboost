package dev.jellyfinnative.data.downloads.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyfinnative.data.downloads.R
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The foreground notification the download worker runs behind (docs/PLAN.md, "Download pipeline":
 * "foreground notification (pause/cancel actions)").
 *
 * It is not decoration. A `CoroutineWorker` that does not promote itself to the foreground is
 * subject to WorkManager's 10-minute execution limit and to the system killing it whenever the app
 * leaves the screen — neither of which a 2 GB transfer survives. The notification is the price
 * Android charges for running that long, so it may as well be useful: it shows the item, the
 * percentage, and the two actions that make a queue feel controllable.
 */
@Singleton
class DownloadNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val manager = NotificationManagerCompat.from(context)

        /** Creates the channel; safe to call repeatedly, and a no-op below API 26 (minSdk is 26). */
        fun ensureChannel() {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.downloads_channel_name),
                    // LOW: an ongoing transfer must not buzz the device every time it re-posts.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.downloads_channel_description)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
        }

        /**
         * The foreground promotion for an actively transferring item.
         *
         * @param bytesTotal `0` while the size is unknown, which renders as an indeterminate bar
         *   rather than as a wrong percentage.
         */
        fun foregroundInfo(
            itemId: UUID,
            title: String,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ): ForegroundInfo =
            ForegroundInfo(
                NOTIFICATION_ID,
                buildNotification(itemId, title, bytesDownloaded, bytesTotal),
                // Declared so API 34+ accepts the promotion; the matching `<service>` override and
                // permission live in this module's manifest.
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

        /** The "queue is starting" state, before any item has reported a byte. */
        fun startingForegroundInfo(): ForegroundInfo =
            ForegroundInfo(
                NOTIFICATION_ID,
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(context.getString(R.string.downloads_notification_preparing))
                    .setOngoing(true)
                    .setSilent(true)
                    .setProgress(0, 0, true)
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

        private fun buildNotification(
            itemId: UUID,
            title: String,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ): Notification {
            val indeterminate = bytesTotal <= 0L
            val percent =
                if (indeterminate) 0 else ((bytesDownloaded * PERCENT) / bytesTotal).toInt().coerceIn(0, PERCENT)

            return NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(
                    if (indeterminate) {
                        context.getString(R.string.downloads_notification_downloading)
                    } else {
                        context.getString(R.string.downloads_notification_percent, percent)
                    },
                ).setProgress(PERCENT, percent, indeterminate)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    context.getString(R.string.downloads_action_pause),
                    actionIntent(DownloadActionReceiver.ACTION_PAUSE, itemId),
                ).addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.downloads_action_cancel),
                    actionIntent(DownloadActionReceiver.ACTION_CANCEL, itemId),
                ).build()
        }

        /**
         * A distinct `PendingIntent` per (action, item).
         *
         * The request code folds both in: two `PendingIntent`s that differ only in their extras are
         * considered equal by the system, so a single request code would leave the Cancel button
         * pausing whatever the Pause button was last built for.
         */
        private fun actionIntent(
            action: String,
            itemId: UUID,
        ): PendingIntent {
            val intent =
                Intent(context, DownloadActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(DownloadActionReceiver.EXTRA_ITEM_ID, itemId.toString())

            return PendingIntent.getBroadcast(
                context,
                (action + itemId).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private companion object {
            const val CHANNEL_ID = "downloads"

            /** One id: the queue runs one item at a time, so one notification is always current. */
            const val NOTIFICATION_ID = 4201

            const val PERCENT = 100
        }
    }
