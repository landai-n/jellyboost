package dev.jellyboost.data.cache

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.ItemCacheKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LibraryViewEntity
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.CacheFixtures.MOVIES_LIBRARY
import dev.jellyboost.data.cache.CacheFixtures.NOW
import dev.jellyboost.data.cache.CacheFixtures.USER_ID
import dev.jellyboost.data.cache.CacheFixtures.entity
import dev.jellyboost.data.cache.CacheFixtures.mapper
import dev.jellyboost.data.cache.CacheFixtures.movieDto
import dev.jellyboost.data.cache.CacheFixtures.uuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [BrowseCacheWriter] and its two non-obvious rules, which are the reason it exists
 * at all.
 *
 * **A browse write must never downgrade a download.** Getting that wrong would be quietly
 * catastrophic — a user scrolling past a film they downloaded would make its row evictable, and the
 * next eviction pass would orphan gigabytes of files on disk with no database row pointing at them.
 * The stored `dto` blob is protected the same way, but only from a **lean** write: the `full` flag
 * is what separates a list response (which must preserve it) from `getItem`'s complete one (which
 * must replace it, so a row an older build gutted can be repaired). Both directions are pinned
 * below, because getting either one wrong leaves a downloaded film with a blank detail page.
 *
 * **A server read refreshes `user_data`, unless the row is pending.** Getting *that* wrong is the
 * corruption bug in STATUS.md: a local row that never learns about a change made from another
 * client is pushed straight back to the server by the next `setPosition`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseCacheWriterTest {
    private val itemDao = mockk<ItemDao>()
    private val libraryViewDao = mockk<LibraryViewDao>()
    private val userDataDao = mockk<UserDataDao>()
    private val sessionRepository = mockk<SessionRepository>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private val upserted = slot<List<ItemEntity>>()
    private val userDataRows = slot<List<UserDataEntity>>()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.getCacheKeys(any()) } returns emptyList()
        coEvery { itemDao.getItems(any()) } returns emptyList()
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { libraryViewDao.upsert(any()) } just Runs
        coEvery { libraryViewDao.deleteExcept(any()) } just Runs
        coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns emptyList()
        coEvery { userDataDao.upsertAll(capture(userDataRows)) } just Runs
        every { sessionRepository.sessionState } returns MutableStateFlow(loggedIn())
    }

    private fun TestScope.writer() =
        BrowseCacheWriter(
            itemDao = itemDao,
            libraryViewDao = libraryViewDao,
            userDataDao = userDataDao,
            sessionRepository = sessionRepository,
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
    fun `browsing past a downloaded item keeps its rich blob, not the lean list DTO`() =
        runTest {
            // The blob DownloadEnqueuer stored at download time — the full response, overview and
            // genres included.
            val richDto =
                movieDto(uuid(1), "Arrival", genres = listOf("Science Fiction", "Drama"))
                    .copy(overview = "A linguist deciphers an alien language.")
            val storedEntity = entity(richDto, source = ItemSource.DOWNLOAD, cachedAt = NOW.minusSeconds(3_600))

            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, storedEntity.cachedAt))
            coEvery { itemDao.getItems(listOf(uuid(1))) } returns listOf(storedEntity)

            // What a browse list read actually sends: no overview, no genres — just the fields the
            // list needs (OnlineJellyfinRepository's list calls request only PRIMARY_IMAGE_ASPECT_RATIO).
            val leanDto = BaseItemDto(id = uuid(1), type = BaseItemKind.MOVIE, name = "Arrival")

            writer().writeItems(listOf(leanDto), full = false)

            val row = upserted.captured.single()
            row.source shouldBe ItemSource.DOWNLOAD
            val rebuilt = mapper.toDtoOrNull(row)
            rebuilt?.overview shouldBe "A linguist deciphers an alien language."
            rebuilt?.genres shouldContainExactly listOf("Science Fiction", "Drama")
        }

    @Test
    fun `a full detail read replaces a downloaded item's blob instead of preserving it`() =
        runTest {
            val stored =
                entity(
                    movieDto(uuid(1), "Arrival", genres = listOf("Drama"))
                        .copy(overview = "The synopsis the server has since corrected."),
                    source = ItemSource.DOWNLOAD,
                    cachedAt = NOW.minusSeconds(3_600),
                )
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, stored.cachedAt))
            coEvery { itemDao.getItems(listOf(uuid(1))) } returns listOf(stored)

            // What `getItem` returns: the complete field set, and therefore strictly better than
            // whatever is on the row.
            val fullDto =
                movieDto(uuid(1), "Arrival", genres = listOf("Science Fiction"))
                    .copy(overview = "A linguist deciphers an alien language.")

            writer().writeItems(listOf(fullDto), full = true)

            val row = upserted.captured.single()
            val rebuilt = mapper.toDtoOrNull(row)
            rebuilt?.overview shouldBe "A linguist deciphers an alien language."
            rebuilt?.genres shouldContainExactly listOf("Science Fiction")
            // The two properties a download keeps whatever the write: it stays a download, and it
            // does not jump to the top of the offline "recently downloaded" rows.
            row.source shouldBe ItemSource.DOWNLOAD
            row.cachedAt shouldBe stored.cachedAt
        }

    @Test
    fun `a full detail read repairs a downloaded item whose blob an older build gutted`() =
        runTest {
            // The state the device walk found: a pre-fix build wrote a lean list DTO over the rich
            // blob, and every later browse write preserved *that*, so the offline detail page was
            // bare forever. Opening the item online has to be able to undo it.
            val gutted =
                entity(
                    BaseItemDto(id = uuid(1), type = BaseItemKind.MOVIE, name = "Arrival"),
                    source = ItemSource.DOWNLOAD,
                    cachedAt = NOW.minusSeconds(86_400),
                )
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, gutted.cachedAt))
            coEvery { itemDao.getItems(listOf(uuid(1))) } returns listOf(gutted)

            val fullDto =
                movieDto(uuid(1), "Arrival", genres = listOf("Science Fiction", "Drama"))
                    .copy(overview = "A linguist deciphers an alien language.")

            writer().writeItems(listOf(fullDto), full = true)

            val rebuilt = mapper.toDtoOrNull(upserted.captured.single())
            rebuilt?.overview shouldBe "A linguist deciphers an alien language."
            rebuilt?.genres shouldContainExactly listOf("Science Fiction", "Drama")
        }

    @Test
    fun `a full detail read does not read the stored blobs back at all`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, NOW))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")), full = true)

            // It is about to overwrite every one of them; fetching multi-kilobyte blobs first would
            // be pure waste.
            coVerify(exactly = 0) { itemDao.getItems(any()) }
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

    // ---- the user-data refresh rule -------------------------------------------------------------

    @Test
    fun `a stale synced row is refreshed from what the server just said`() =
        runTest {
            // The exact STATUS.md repro: the row says watched, the server says it is not.
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns emptyList()

            writer().writeItems(
                listOf(movieDto(uuid(1), "Citizen Vigilante").withUserData(played = false)),
            )

            val row = userDataRows.captured.single()
            row.itemId shouldBe uuid(1)
            row.userId shouldBe USER_ID
            row.played shouldBe false
            row.toBeSynced shouldBe false
        }

    @Test
    fun `a row waiting to be pushed is left completely untouched`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns listOf(uuid(1))

            writer().writeItems(
                listOf(movieDto(uuid(1), "Citizen Vigilante").withUserData(played = false)),
            )

            // The pending row is the only copy of a change the server has not accepted; adopting the
            // server's older value here would silently discard it. Reconciling the two is M8's job.
            coVerify(exactly = 0) { userDataDao.upsertAll(any()) }
        }

    @Test
    fun `an item with no local row at all gets one from the server`() =
        runTest {
            writer().writeItems(
                listOf(
                    movieDto(uuid(1), "Arrival").withUserData(
                        played = true,
                        isFavorite = true,
                        positionTicks = 42L,
                        lastPlayedDate = PLAYED_AT,
                    ),
                ),
            )

            val row = userDataRows.captured.single()
            row.played shouldBe true
            row.isFavorite shouldBe true
            row.playbackPositionTicks shouldBe 42L
            row.lastPlayedDate shouldBe PLAYED_AT.atZone(ZoneId.systemDefault()).toInstant()
        }

    @Test
    fun `adopting server state records when the mirror was refreshed, not a newer-than-server write`() =
        runTest {
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData()))

            val row = userDataRows.captured.single()
            // `updatedAt` is the moment this device learned the server's state...
            row.updatedAt shouldBe NOW
            // ...and `lastPlayedDate` stays the server's own value — never invented from the read.
            row.lastPlayedDate.shouldBeNull()
            // With the flag clear, the row is never fed to most-recent-wins in the first place.
            row.toBeSynced shouldBe false
        }

    @Test
    fun `a response carrying no user data writes no user-data rows`() =
        runTest {
            // `enableUserData` off, or an endpoint that omits the block: silence is not "unwatched".
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            coVerify(exactly = 0) { userDataDao.getPendingSyncIds(any(), any()) }
            coVerify(exactly = 0) { userDataDao.upsertAll(any()) }
        }

    @Test
    fun `refreshes every non-pending item of a mixed page`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns listOf(uuid(2))

            writer().writeItems(
                listOf(
                    movieDto(uuid(1), "Arrival").withUserData(played = true),
                    movieDto(uuid(2), "Dune").withUserData(played = true),
                    movieDto(uuid(3), "Sicario"),
                    movieDto(uuid(4), "Prisoners").withUserData(played = false),
                ),
            )

            // Only the two rows that have server user data and are not pending.
            userDataRows.captured.map { it.itemId } shouldContainExactly listOf(uuid(1), uuid(4))
            // The pending row's id is still asked about — that is how it gets excluded.
            coVerify(exactly = 1) {
                userDataDao.getPendingSyncIds(listOf(uuid(1), uuid(2), uuid(4)), USER_ID)
            }
        }

    @Test
    fun `writes no user data when nobody is signed in`() =
        runTest {
            every { sessionRepository.sessionState } returns MutableStateFlow(SessionState.LoggedOut)

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            // No userId to key rows on. The metadata half still runs.
            coVerify(exactly = 0) { userDataDao.upsertAll(any()) }
            coVerify(exactly = 1) { itemDao.upsert(any()) }
        }

    @Test
    fun `a failing user-data refresh still leaves the metadata cached`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } throws SQLiteException("disk full")

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            coVerify(exactly = 1) { itemDao.upsert(any()) }
        }

    @Test
    fun `a failing metadata write still refreshes the user data`() =
        runTest {
            coEvery { itemDao.upsert(any()) } throws SQLiteException("disk full")

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            userDataRows.captured.single().played shouldBe true
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
            coEvery { itemDao.upsert(any()) } throws SQLiteException("disk full")

            // The caller already has its data; a broken cache must not fail their read.
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))
        }

    /**
     * A cancellation is not a failed write: these guards run on the never-cancelled application
     * scope today, but swallowing one turns any future structured cancellation of this writer into
     * a silent no-op that logs a warning and reports success (audit STAB-06).
     */
    @Test
    fun `a cancelled item write propagates instead of being logged as a failure`() =
        runTest {
            coEvery { itemDao.upsert(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))
            }
        }

    @Test
    fun `a cancelled user-data refresh propagates`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } throws
                CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))
            }
        }

    @Test
    fun `a cancelled library-view write propagates`() =
        runTest {
            coEvery { libraryViewDao.upsert(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                writer().writeViews(listOf(library(MOVIES_LIBRARY, "Films", CollectionType.MOVIES)))
            }
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
        id: UUID,
        name: String,
        collectionType: CollectionType,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.COLLECTION_FOLDER,
            name = name,
            collectionType = collectionType,
        )

    /** Attaches the `userData` block a real `enableUserData = true` response would carry. */
    private fun BaseItemDto.withUserData(
        played: Boolean = false,
        isFavorite: Boolean = false,
        positionTicks: Long = 0L,
        lastPlayedDate: LocalDateTime? = null,
    ): BaseItemDto =
        copy(
            userData =
                UserItemDataDto(
                    playbackPositionTicks = positionTicks,
                    playCount = if (played) 1 else 0,
                    isFavorite = isFavorite,
                    played = played,
                    lastPlayedDate = lastPlayedDate,
                    key = id.toString(),
                    itemId = id,
                ),
        )

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.randomUUID(),
            userId = USER_ID,
            userName = "casey",
            serverName = "home",
            serverVersion = "10.11.0",
        )

    private companion object {
        /** The SDK reads its date fields in the device's zone, so this is a local wall-clock time. */
        val PLAYED_AT: LocalDateTime = LocalDateTime.of(2026, 7, 20, 21, 30)
    }
}
