package dev.jellyfinnative.data.cache

import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.dao.LibraryViewDao
import dev.jellyfinnative.core.database.entities.ItemCacheKey
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.database.entities.LibraryViewEntity
import dev.jellyfinnative.data.cache.CacheFixtures.MOVIES_LIBRARY
import dev.jellyfinnative.data.cache.CacheFixtures.NOW
import dev.jellyfinnative.data.cache.CacheFixtures.mapper
import dev.jellyfinnative.data.cache.CacheFixtures.movieDto
import dev.jellyfinnative.data.cache.CacheFixtures.uuid
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [BrowseCacheWriter], whose one non-obvious rule is the reason it exists at all:
 * **a browse write must never downgrade a download**.
 *
 * Getting that wrong would be quietly catastrophic — a user scrolling past a film they downloaded
 * would make its row evictable, and the next eviction pass would orphan gigabytes of files on disk
 * with no database row pointing at them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseCacheWriterTest {
    private val itemDao = mockk<ItemDao>()
    private val libraryViewDao = mockk<LibraryViewDao>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private val upserted = slot<List<ItemEntity>>()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.getCacheKeys(any()) } returns emptyList()
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { libraryViewDao.upsert(any()) } just Runs
        coEvery { libraryViewDao.deleteExcept(any()) } just Runs
    }

    private fun TestScope.writer() =
        BrowseCacheWriter(
            itemDao = itemDao,
            libraryViewDao = libraryViewDao,
            mapper = mapper,
            clock = clock,
            scope = this,
        )

    // ---- the merge rule -----------------------------------------------------------------------

    @Test
    fun `a browsed item that is not cached yet becomes a browse-cache row`() =
        runTest {
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            upserted.captured.single().source shouldBe ItemSource.BROWSE_CACHE
            upserted.captured.single().cachedAt shouldBe NOW
        }

    @Test
    fun `browsing past a downloaded item never demotes it to browse cache`() =
        runTest {
            val downloadedAt = Instant.parse("2026-07-01T08:00:00Z")
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, downloadedAt))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            upserted.captured.single().source shouldBe ItemSource.DOWNLOAD
        }

    @Test
    fun `browsing past a downloaded item does not reshuffle the recently-downloaded rows`() =
        runTest {
            val downloadedAt = Instant.parse("2026-07-01T08:00:00Z")
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, downloadedAt))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            // The offline "Latest" rows order by cachedAt; bumping it here would silently reorder
            // them every time the user opened the home screen online.
            upserted.captured.single().cachedAt shouldBe downloadedAt
        }

    @Test
    fun `still refreshes the metadata of a downloaded item`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, NOW))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival (Director's Cut)")))

            upserted.captured.single().name shouldBe "Arrival (Director's Cut)"
        }

    @Test
    fun `an existing browse-cache row is refreshed in place`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.BROWSE_CACHE, NOW.minusSeconds(3_600)))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            upserted.captured.single().source shouldBe ItemSource.BROWSE_CACHE
            upserted.captured.single().cachedAt shouldBe NOW
        }

    @Test
    fun `merges each item against its own stored row`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(2), ItemSource.DOWNLOAD, NOW))

            writer().writeItems(
                listOf(movieDto(uuid(1), "Arrival"), movieDto(uuid(2), "Dune"), movieDto(uuid(3), "Sicario")),
            )

            upserted.captured.map { it.source } shouldContainExactly
                listOf(ItemSource.BROWSE_CACHE, ItemSource.DOWNLOAD, ItemSource.BROWSE_CACHE)
        }

    // ---- resilience ---------------------------------------------------------------------------

    @Test
    fun `writes nothing at all for an empty response`() =
        runTest {
            writer().cacheItems(emptyList())

            coVerify(exactly = 0) { itemDao.upsert(any()) }
            coVerify(exactly = 0) { itemDao.getCacheKeys(any()) }
        }

    @Test
    fun `a failed cache write is swallowed, never surfaced to the caller`() =
        runTest {
            coEvery { itemDao.upsert(any()) } throws IllegalStateException("disk full")

            // The caller already has its data; a broken cache must not fail their read.
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))
        }

    // ---- library views ------------------------------------------------------------------------

    @Test
    fun `caches supported libraries in server order and drops the rest`() =
        runTest {
            val rows = slot<List<LibraryViewEntity>>()
            coEvery { libraryViewDao.upsert(capture(rows)) } just Runs

            writer().writeViews(
                listOf(
                    library(MOVIES_LIBRARY, "Films", CollectionType.MOVIES),
                    library(uuid(50), "Musique", CollectionType.MUSIC),
                    library(uuid(51), "Séries", CollectionType.TVSHOWS),
                ),
            )

            rows.captured.map { it.name } shouldContainExactly listOf("Films", "Séries")
            // The index is the *response* position, so the offline list matches the online one even
            // though the music library in between was dropped.
            rows.captured.map { it.sortIndex } shouldContainExactly listOf(0, 2)
        }

    @Test
    fun `prunes libraries the server no longer reports`() =
        runTest {
            writer().writeViews(listOf(library(MOVIES_LIBRARY, "Films", CollectionType.MOVIES)))

            coVerify(exactly = 1) { libraryViewDao.deleteExcept(listOf(MOVIES_LIBRARY)) }
        }

    @Test
    fun `never wipes the cached libraries when nothing supported came back`() =
        runTest {
            writer().writeViews(listOf(library(uuid(50), "Musique", CollectionType.MUSIC)))

            coVerify(exactly = 0) { libraryViewDao.deleteExcept(any()) }
            coVerify(exactly = 0) { libraryViewDao.upsert(any()) }
        }

    private fun library(
        id: java.util.UUID,
        name: String,
        collectionType: CollectionType,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.COLLECTION_FOLDER,
            name = name,
            collectionType = collectionType,
        )
}
