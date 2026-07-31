package dev.jellyboost.data.downloads.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.jellyboost.core.network.di.ApplicationScope
import dev.jellyboost.data.downloads.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Backs the two actions on the download notification.
 *
 * A receiver rather than an activity so that pausing a download does not have to bring the app to
 * the foreground — the whole point of the notification is that the queue is controllable from
 * wherever the user happens to be.
 *
 * `goAsync()` plus the application scope, because both actions touch Room and WorkManager and a
 * receiver's `onReceive` runs on the main thread with a hard time limit. The `PendingResult` is
 * finished in the coroutine's completion handler so the system stops holding the process open the
 * moment the work is done.
 */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var downloads: DownloadRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val action = intent.action ?: return

        val pendingResult = goAsync()
        val job =
            scope.launch {
                when (action) {
                    ACTION_PAUSE -> downloads.pause(itemId)
                    ACTION_CANCEL -> downloads.delete(itemId)
                    else -> Timber.w("Unknown download action %s", action)
                }
            }
        job.invokeOnCompletion { pendingResult.finish() }
    }

    companion object {
        /** Pauses the item currently transferring; its partial files stay on disk. */
        const val ACTION_PAUSE = "dev.jellyboost.downloads.PAUSE"

        /** Cancels the item outright — same cascade as deleting a finished download. */
        const val ACTION_CANCEL = "dev.jellyboost.downloads.CANCEL"

        /** Item id, as a string; a `UUID` cannot travel in an intent extra without parcelling. */
        const val EXTRA_ITEM_ID = "itemId"
    }
}
