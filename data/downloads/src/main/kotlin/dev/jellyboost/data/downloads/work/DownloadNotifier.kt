package dev.jellyboost.data.downloads.work

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
import dev.jellyboost.data.downloads.R
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
internal class DownloadNotifier
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

        /** The last progress this notifier actually posted — see [foregroundInfoIfChanged]. */
        private var lastPosted: NotificationProgress? = null

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

        /**
         * [foregroundInfo], or `null` when nothing the user would see has changed since the last
         * call.
         *
         * The throttle posts progress at up to six times a second; the whole percentage it renders
         * moves far less often than that. Every call used to rebuild the `Notification` and its two
         * `PendingIntent`s regardless, which is work spent on byte deltas nobody sees
         * (docs/notes/audit-2026-07.md, PERF-12). One item downloads at a time, so the single
         * [lastPosted] field is all the state this needs — a new item's first call always differs
         * from whatever the previous item last posted.
         */
        fun foregroundInfoIfChanged(
            itemId: UUID,
            title: String,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ): ForegroundInfo? {
            val progress = notificationProgressOf(itemId, title, bytesDownloaded, bytesTotal)
            if (progress == lastPosted) return null
            lastPosted = progress
            return foregroundInfo(itemId, title, bytesDownloaded, bytesTotal)
        }

        /**
         * Forgets what was last posted, so the next sample is *always* considered a change.
         *
         * This notifier is a `@Singleton` and [lastPosted] therefore outlives a worker run, while
         * the notification it describes does not: every worker run opens with
         * [startingForegroundInfo] ("Preparing…"), which does not go through
         * [foregroundInfoIfChanged] and so leaves the field holding the previous run's figure. A
         * pause and an immediate resume then produced a first sample equal to it, the promotion was
         * skipped as "nothing the user would see changed", and the notification sat on *Preparing…*
         * until the whole percent happened to tick over — for a paused-at-73 %-of-4 GB episode,
         * tens of seconds (audit 2026-08-08, PERF-15).
         *
         * Called from both ends of a run: the worker resets before it posts *Preparing…* (a pause
         * cancels the worker outright, so the idle path below never runs for the case that actually
         * bites), and the queue's `onIdle` resets when it runs dry with the worker still alive.
         */
        fun resetPostedProgress() {
            lastPosted = null
        }

        /** The "queue is starting" state, before any item has reported a byte. */
        fun startingForegroundInfo(): ForegroundInfo =
            ForegroundInfo(
                NOTIFICATION_ID,
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_fin)
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
            val progress = notificationProgressOf(itemId, title, bytesDownloaded, bytesTotal)

            // SEC-07: the real title names what the user is downloading — a show or a film — and
            // that is exactly the kind of thing "sensitive content" lockscreen settings exist to
            // hide. VISIBILITY_PRIVATE plus a generic public version means a locked device with that
            // setting on shows "Downloading…" instead, never the title.
            val publicVersion =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_fin)
                    .setContentTitle(context.getString(R.string.downloads_notification_generic_title))
                    .setProgress(PERCENT, progress.percent, progress.indeterminate)
                    .setOngoing(true)
                    .setSilent(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build()

            return NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_fin)
                .setContentTitle(title)
                .setContentText(
                    if (progress.indeterminate) {
                        context.getString(R.string.downloads_notification_downloading)
                    } else {
                        context.getString(R.string.downloads_notification_percent, progress.percent)
                    },
                ).setProgress(PERCENT, progress.percent, progress.indeterminate)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
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

/**
 * Everything a downloads notification renders that the user can actually see: which item, and
 * either a whole percentage or "indeterminate".
 *
 * Byte counts are deliberately absent — they are what changes on every throttled progress write,
 * and `percent` already rounds almost all of those away. Two of these being `==` is the whole of
 * [DownloadNotifier.foregroundInfoIfChanged]'s change guard (docs/notes/audit-2026-07.md, PERF-12),
 * and keeping this a plain data class free of `Context`/`Notification` is what lets that guard's
 * decision be pinned by a JVM test with none of the Android framework in the way.
 */
internal data class NotificationProgress(
    val itemId: UUID,
    val title: String,
    val percent: Int,
    val indeterminate: Boolean,
)

/** [NotificationProgress] for one progress report, rounding to the same whole percent the bar shows. */
internal fun notificationProgressOf(
    itemId: UUID,
    title: String,
    bytesDownloaded: Long,
    bytesTotal: Long,
): NotificationProgress {
    val indeterminate = bytesTotal <= 0L
    val percent =
        if (indeterminate) {
            0
        } else {
            ((bytesDownloaded * NOTIFICATION_PERCENT) / bytesTotal).toInt().coerceIn(0, NOTIFICATION_PERCENT)
        }
    return NotificationProgress(itemId, title, percent, indeterminate)
}

private const val NOTIFICATION_PERCENT = 100
