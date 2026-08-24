package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem

/**
 * Keeps a queue row's displayed percentage from ever going backwards.
 *
 * It exists because the denominator moves. A transcoded download's total is a *projection*,
 * re-measured on every throttled write, and a projection that grows — the encoder hitting a busy
 * scene, a seed from the show's easier episodes being corrected upwards — lowers `bytes / total`
 * even though not one byte was lost. A progress bar that retreats reads as a failure, and the user
 * has no way to tell it apart from one.
 *
 * So the fraction shown for an item is the **highest** one it has reached this session. The bar
 * stalls instead of reversing, which is honest about the only thing it claims: how much of the item
 * is on the device is monotone, and the bar is too.
 *
 * ### The 99 % hold
 * Nothing but [DownloadStatus.DOWNLOADED] is allowed to draw a full bar. An estimate that undershot
 * reaches 100 % while the encoder is still working, and a bar sitting full through the last minute
 * of a transfer is a worse lie than one sitting at 99 % — this is the same reasoning that made
 * `ItemProgress` drop its estimate once every real size is known, applied to the pixels.
 *
 * ### Deliberate consequence
 * A transcoded download that is interrupted restarts from zero (the server ignores `Range`), and
 * this holds its bar at the height the abandoned attempt reached rather than dropping it back. That
 * is the ratchet doing its job, not a bug: the alternative is the retreating bar this class exists
 * to prevent. The figure beside it still reports the real bytes. An item that leaves the list
 * entirely — deleted, then downloaded again — is forgotten and starts over.
 *
 * State lives here rather than in a Room column for the same reason [DownloadSpeedTracker]'s does:
 * it is a property of *this* session's screen, not of the download, and it must not survive a
 * process death that resets everything the user was looking at.
 */
internal class DownloadProgressRatchet {
    private val shown = mutableMapOf<String, Float>()

    /**
     * @param queue the rows that draw a bar — `toQueue()`'s subset, not the whole table.
     *   Only queue rows read this map, and building a set and a map over
     *   every download ever made, several times a second, to answer for a handful of them is work
     *   that grows with the user's library rather than with the queue. [DownloadSpeedTracker]
     *   filters first for the same reason.
     * @return the fraction to draw for each item, keyed by item id, in the order given.
     */
    fun update(queue: List<DownloadItem>): Map<String, Float> {
        // Rows that are gone are forgotten, so a re-download after a delete starts at zero rather
        // than inheriting the bar of the item it replaced. A row that finished has left the queue,
        // which is the same thing as far as this map is concerned.
        shown.keys.retainAll(queue.mapTo(mutableSetOf()) { it.itemId })

        return queue.associate { item ->
            item.itemId to
                // `toQueue()` never hands this a finished row, so this branch is the guard for a
                // caller that does — one that passed the whole table would otherwise ratchet a
                // completed download's bar to 99 % and hold it there.
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
