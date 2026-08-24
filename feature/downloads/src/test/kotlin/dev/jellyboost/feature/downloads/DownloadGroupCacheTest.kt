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
 * Two properties. **Identity**: an unchanged finished half returns the *same* list instance, which
 * is what lets visible rows skip recomposition. **Exactness**: anything a row draws from — title,
 * size, artwork metadata, resume position — invalidates the answer.
 */
class DownloadGroupCacheTest {
    private val cache = DownloadGroupCache()

    @Test
    fun `an unchanged table gives back the same list, not an equal one`() {
        val items = listOf(finished("1"), finished("2"))

        val first = cache.groups(items)
        // Fresh-but-equal rows, which is what Room hands the projection on every emission.
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
        // Why whole rows are compared rather than a key of ids and byte counts: artwork arriving
        // with a metadata refresh would otherwise never show.
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
