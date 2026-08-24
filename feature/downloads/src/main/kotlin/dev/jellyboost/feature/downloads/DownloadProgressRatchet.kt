package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem

/**
 * Keeps a queue row's displayed percentage from going backwards. The denominator moves: a transcoded
 * download's total is a projection re-measured on every throttled write, and a projection that grows
 * lowers `bytes / total` without a byte being lost.
 *
 * Nothing but [DownloadStatus.DOWNLOADED] may draw a full bar — an undershooting estimate reaches
 * 100 % while the encoder is still working.
 *
 * **Deliberate consequence:** an interrupted transcode restarts from zero (the server ignores
 * `Range`) and this holds its bar where the abandoned attempt left it. That is the ratchet working;
 * the byte figure beside it still reports the truth. An item that leaves the list is forgotten.
 *
 * Session state, never a Room column: it belongs to this screen, not to the download.
 */
internal class DownloadProgressRatchet {
    private val shown = mutableMapOf<String, Float>()

    /**
     * @param queue `toQueue()`'s subset, **not** the whole table: mapping every download ever made,
     *   several times a second, is work that grows with the library rather than with the queue.
     */
    fun update(queue: List<DownloadItem>): Map<String, Float> {
        // Gone rows must be forgotten, or a re-download after a delete inherits the old bar.
        shown.keys.retainAll(queue.mapTo(mutableSetOf()) { it.itemId })

        return queue.associate { item ->
            item.itemId to
                // `toQueue()` never hands this a finished row; the guard is for a caller that
                // passes the whole table, which would ratchet a completed bar to 99 % and hold it.
                if (item.status == DownloadStatus.DOWNLOADED) {
                    shown.remove(item.itemId)
                    1f
                } else {
                    val value = maxOf(item.progress, shown[item.itemId] ?: 0f).coerceAtMost(HOLD_AT)
                    shown[item.itemId] = value
                    value
                }
        }
    }

    private companion object {
        /** Only a `DOWNLOADED` row draws a full bar; everything else stops one percent short. */
        const val HOLD_AT = 0.99f
    }
}
