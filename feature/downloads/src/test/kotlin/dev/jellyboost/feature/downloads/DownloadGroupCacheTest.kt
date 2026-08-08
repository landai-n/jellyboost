package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.model.DownloadItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DownloadGroupCache] — the memoisation the 2026-08-08 audit asked for (PERF-11).
 *
 * Two properties, and the second is the one that makes it safe. **Identity**: while the finished
 * half of the table is unchanged, the *same* `List<DownloadGroup>` comes back, which is what lets
 * every visible finished row skip recomposition through a transfer that re-emits several times a
 * second. **Exactness**: anything a row draws from — its title, its size, the metadata behind its
 * artwork and its resume position — invalidates the answer, because the groups hold the very
 * `DownloadItem`s the rows read.
 */
class DownloadGroupCacheTest {
    private val cache = DownloadGroupCache()

    @Test
    fun `an unchanged table gives back the same list, not an equal one`() {
        val items = listOf(finished("1"), finished("2"))

        val first = cache.groups(items)
        // A fresh list of fresh-but-equal rows, which is exactly what Room hands the projection on
        // every emission — nothing about it is the same instance as the last one.
        val second = cache.groups(listOf(finished("1"), finished("2")))

        second shouldBeSameInstanceAs first
    }

    @Test
    fun `a queue writing progress does not disturb the finished half`() {
        val finished = listOf(finished("1"))

        val first = cache.groups(finished + downloading(bytes = 100L))
        val second = cache.groups(finished + downloading(bytes = 200L))

        second shouldBeSameInstanceAs first
    }

    @Test
    fun `a new download regroups`() {
        val first = cache.groups(listOf(finished("1")))
        val second = cache.groups(listOf(finished("1"), finished("2")))

        second shouldNotBeSameInstanceAs first
        second.flatMap { it.items }.map { it.itemId } shouldBe listOf("1", "2")
    }

    @Test
    fun `a deleted download regroups`() {
        cache.groups(listOf(finished("1"), finished("2")))

        cache.groups(listOf(finished("1"))).flatMap { it.items }.map { it.itemId } shouldBe listOf("1")
    }

    @Test
    fun `a size that changed regroups, since a group header draws it`() {
        val first = cache.groups(listOf(finished("1", bytesOnDisk = 100L)))
        val second = cache.groups(listOf(finished("1", bytesOnDisk = 200L)))

        second shouldNotBeSameInstanceAs first
        second.single().bytesOnDisk shouldBe 200L
    }

    @Test
    fun `metadata arriving regroups, even though nothing about the grouping changed`() {
        // The reason the cache compares whole rows rather than a key of ids and byte counts: the
        // groups hold the items the rows draw *from*, so artwork that arrives with a metadata
        // refresh (or a resume position written by another screen) would otherwise never show.
        val withoutArtwork = finished("1")
        val first = cache.groups(listOf(withoutArtwork))

        val second = cache.groups(listOf(withoutArtwork.copy(item = jellyfinItem())))

        second shouldNotBeSameInstanceAs first
        second
            .single()
            .items
            .single()
            .item shouldBe jellyfinItem()
    }

    @Test
    fun `a rename regroups, since the tab is ordered by title`() {
        val first = cache.groups(listOf(finished("1")))
        val second = cache.groups(listOf(finished("1").copy(title = "Renamed")))

        second shouldNotBeSameInstanceAs first
        second.single().title shouldBe "Renamed"
    }

    @Test
    fun `an empty table gives an empty list`() {
        cache.groups(listOf(downloading(bytes = 10L))) shouldBe emptyList()
    }

    private fun finished(
        itemId: String,
        bytesOnDisk: Long = 100L,
    ) = DownloadItem(
        itemId = itemId,
        title = "Title $itemId",
        seriesName = null,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = bytesOnDisk,
        bytesTotal = bytesOnDisk,
        bytesOnDisk = bytesOnDisk,
        queuePosition = 0,
    )

    private fun downloading(bytes: Long) =
        DownloadItem(
            itemId = "queued",
            title = "Queued",
            seriesName = null,
            status = DownloadStatus.DOWNLOADING,
            bytesDownloaded = bytes,
            bytesTotal = 1_000L,
            bytesOnDisk = bytes,
            queuePosition = 0,
        )

    private fun jellyfinItem() =
        JellyfinItem(
            id = "1",
            name = "Title 1",
            type = ItemType.MOVIE,
            primaryImageUrl = "https://example.invalid/1.jpg",
        )
}
