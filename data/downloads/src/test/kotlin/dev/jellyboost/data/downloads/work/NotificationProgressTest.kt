package dev.jellyboost.data.downloads.work

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [notificationProgressOf] — the whole of [DownloadNotifier.foregroundInfoIfChanged]'s
 * change guard (docs/notes/audit-2026-07.md, PERF-12).
 *
 * `DownloadNotifier` itself needs a `Context` to build a real `Notification`, but the decision the
 * guard makes — has anything the user would see actually changed? — depends only on these four
 * values, which is why it is pulled out as its own function: this is what lets it be pinned without
 * any of the Android framework in the way.
 */
class NotificationProgressTest {
    @Test
    fun `two byte counts that round to the same percent report as unchanged`() {
        val itemId = UUID.randomUUID()

        val first = notificationProgressOf(itemId, "Arrival", bytesDownloaded = 500L, bytesTotal = 1_000L)
        val second = notificationProgressOf(itemId, "Arrival", bytesDownloaded = 504L, bytesTotal = 1_000L)

        // Both round to 50%; the guard must see these as the same notification.
        second shouldBe first
    }

    @Test
    fun `crossing a whole percent point reports as changed`() {
        val itemId = UUID.randomUUID()

        val first = notificationProgressOf(itemId, "Arrival", bytesDownloaded = 500L, bytesTotal = 1_000L)
        val second = notificationProgressOf(itemId, "Arrival", bytesDownloaded = 510L, bytesTotal = 1_000L)

        second shouldNotBe first
    }

    @Test
    fun `an unknown size is always indeterminate at zero percent`() {
        val progress = notificationProgressOf(UUID.randomUUID(), "Arrival", bytesDownloaded = 500L, bytesTotal = 0L)

        progress.indeterminate shouldBe true
        progress.percent shouldBe 0
    }

    @Test
    fun `a new item never reads as unchanged from the previous one`() {
        val first = notificationProgressOf(UUID.randomUUID(), "Arrival", bytesDownloaded = 500L, bytesTotal = 1_000L)
        val second = notificationProgressOf(UUID.randomUUID(), "Chestnut", bytesDownloaded = 500L, bytesTotal = 1_000L)

        // The queue runs one item at a time; switching items must always force a rebuild, even at
        // the same percent, or the notification would keep the previous item's title.
        second shouldNotBe first
    }

    @Test
    fun `percent is clamped even if bytesDownloaded briefly overshoots the total`() {
        val progress =
            notificationProgressOf(UUID.randomUUID(), "Arrival", bytesDownloaded = 1_050L, bytesTotal = 1_000L)

        progress.percent shouldBe 100
    }
}
