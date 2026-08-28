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

        val first = cache.sections(items)
        // Fresh-but-equal rows, which is what Room hands the projection on every emission.
        val second = cache.sections(listOf(finished("1"), finished("2")))

        second shouldBeSameInstanceAs first
    }

    @Test
    fun `a queue writing progress does not disturb the finished half`() {
        val finished = listOf(finished("1"))

        val first = cache.sections(finished + downloading(bytes = 100L))
        val second = cache.sections(finished + downloading(bytes = 200L))

        second shouldBeSameInstanceAs first
    }

    @Test
    fun `a new download regroups`() {
        val first = cache.sections(listOf(finished("1")))
        val second = cache.sections(listOf(finished("1"), finished("2")))

        second shouldNotBeSameInstanceAs first
        second.rows().map { it.itemId } shouldBe listOf("1", "2")
    }

    @Test
    fun `a deleted download regroups`() {
        cache.sections(listOf(finished("1"), finished("2")))

        cache.sections(listOf(finished("1"))).rows().map { it.itemId } shouldBe listOf("1")
    }

    @Test
    fun `a size that changed regroups, since a group header draws it`() {
        val first = cache.sections(listOf(finished("1", bytesOnDisk = 100L)))
        val second = cache.sections(listOf(finished("1", bytesOnDisk = 200L)))

        second shouldNotBeSameInstanceAs first
        second
            .single()
            .groups
            .single()
            .bytesOnDisk shouldBe 200L
    }

    @Test
    fun `metadata arriving regroups, even though nothing about the grouping changed`() {
        // Why whole rows are compared rather than a key of ids and byte counts: artwork arriving
        // with a metadata refresh would otherwise never show.
        val withoutArtwork = finished("1")
        val first = cache.sections(listOf(withoutArtwork))

        val second = cache.sections(listOf(withoutArtwork.copy(item = jellyfinItem())))

        second shouldNotBeSameInstanceAs first
        second
            .rows()
            .single()
            .item shouldBe jellyfinItem()
    }

    @Test
    fun `an artist arriving regroups, since an album header draws it`() {
        // The backfill lands the artist long after the row itself; a signature of ids, statuses and
        // byte counts would leave the header crediting nobody until some unrelated write came along.
        val withoutArtist = track("1", album = "Rumours")
        val first = cache.sections(listOf(withoutArtist))

        val second = cache.sections(listOf(withoutArtist.copy(artistName = "Fleetwood Mac")))

        second shouldNotBeSameInstanceAs first
        second
            .single()
            .groups
            .single()
            .subtitle shouldBe "Fleetwood Mac"
    }

    @Test
    fun `a season poster arriving regroups, since a series header draws it`() {
        // The season's own row is written by the metadata refresh, long after the episode; a
        // signature of ids, statuses and byte counts would leave the header posterless until some
        // unrelated write came along. Same reason as the artist above, on the other kind's header.
        val withoutPoster = episode("1", series = "Westworld")
        val first = cache.sections(listOf(withoutPoster))

        val second =
            cache.sections(
                listOf(withoutPoster.copy(seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg")),
            )

        second shouldNotBeSameInstanceAs first
        val group = second.single().groups.single()
        group.artworkUrl shouldBe "https://example.invalid/westworld-s1.jpg"
        group.subtitle shouldBe "Season 1"
    }

    @Test
    fun `a rename regroups, since the tab is ordered by title`() {
        val first = cache.sections(listOf(finished("1")))
        val second = cache.sections(listOf(finished("1").copy(title = "Renamed")))

        second shouldNotBeSameInstanceAs first
        second.rows().single().title shouldBe "Renamed"
    }

    @Test
    fun `an empty table gives an empty list`() {
        cache.sections(listOf(downloading(bytes = 10L))) shouldBe emptyList()
    }

    private fun List<DownloadSection>.rows() = flatMap { section -> section.groups.flatMap { it.items } }

    private fun track(
        itemId: String,
        album: String,
    ) = DownloadItem(
        itemId = itemId,
        title = "Track $itemId",
        seriesName = null,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = 100L,
        bytesTotal = 100L,
        bytesOnDisk = 100L,
        queuePosition = 0,
        itemType = ItemType.AUDIO,
        albumName = album,
    )

    private fun episode(
        itemId: String,
        series: String,
    ) = DownloadItem(
        itemId = itemId,
        title = "Episode $itemId",
        seriesName = series,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = 100L,
        bytesTotal = 100L,
        bytesOnDisk = 100L,
        queuePosition = 0,
        itemType = ItemType.EPISODE,
        item =
            JellyfinItem(
                id = itemId,
                name = "Episode $itemId",
                type = ItemType.EPISODE,
                seasonName = "Season 1",
                parentIndexNumber = 1,
            ),
    )

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
