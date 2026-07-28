package dev.jellyfinnative.data.downloads.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.data.downloads.engine.DownloadQueue
import dev.jellyfinnative.data.downloads.engine.DownloadQueueListener
import timber.log.Timber

/**
 * Runs [DownloadQueue] as foreground work.
 *
 * The worker owns almost nothing: WorkManager decides *when* it may run (the Wi-Fi-only and
 * storage constraints `WorkManagerDownloadScheduler` attaches), the queue decides *what* runs, and
 * this class is the bit in between that keeps a foreground notification current so Android lets the
 * transfer take as long as it takes.
 *
 * A failure inside the queue is not a worker failure: the item is already marked
 * `DownloadStatus.ERROR` in Room and shown as such in the Queue tab, and retrying the whole job on
 * a permanently broken item (deleted on the server, unreadable file) would loop forever. Only a
 * failure of the *machinery* — storage vanishing, a Room error — is retried.
 */
@HiltWorker
class DownloadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val queue: DownloadQueue,
        private val notifier: DownloadNotifier,
    ) : CoroutineWorker(appContext, workerParameters) {
        override suspend fun doWork(): Result {
            notifier.ensureChannel()
            promote { notifier.startingForegroundInfo() }

            return try {
                val drained = queue.drain(listener)
                if (!drained) Timber.w("Download queue drained with at least one failed item")
                Result.success()
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.e(error, "Download queue stopped unexpectedly")
                Result.retry()
            }
        }

        private val listener =
            object : DownloadQueueListener {
                override suspend fun onProgress(
                    download: DownloadEntity,
                    bytesDownloaded: Long,
                    bytesTotal: Long,
                ) {
                    promote {
                        notifier.foregroundInfo(
                            itemId = download.itemId,
                            title = download.notificationTitle(),
                            bytesDownloaded = bytesDownloaded,
                            bytesTotal = bytesTotal,
                        )
                    }
                }

                override suspend fun onIdle() = Unit
            }

        /**
         * Posts a foreground update, swallowing the refusal.
         *
         * `setForeground` throws when the process is not allowed to start a foreground service —
         * a short window on some OEM builds, and after the user revokes the notification
         * permission. Losing the *notification* is survivable; losing the download because of it is
         * not.
         */
        private suspend fun promote(info: () -> androidx.work.ForegroundInfo) {
            runCatching { setForeground(info()) }
                .onFailure { Timber.w(it, "Could not show the download notification") }
        }
    }

/** `Westworld — S01E02 · Chestnut` for an episode, the plain title otherwise. */
private fun DownloadEntity.notificationTitle(): String =
    seriesName?.takeIf { it.isNotBlank() }?.let { "$it — $itemName" } ?: itemName
