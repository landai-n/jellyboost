package dev.jellyboost.data.userdata

import app.cash.turbine.test
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.UserDataEntity
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

/**
 * The most-recent-wins decision matrix (docs/PLAN.md, "Confirmed decisions": *"User-data sync
 * conflict: most-recent-wins — compare `lastPlayedDate` before pushing; keep newer position"*).
 *
 * This is the densest logic in M8 and the one the milestone's definition of done runs through:
 * an airplane-mode session leaves a pending row, and what happens to it on reconnect is decided
 * here. Every branch — local newer, server newer, a tie, no server date, no server user data at
 * all, transport failure, a deleted item, and a batch where only some rows fail — has its own test.
 *
 * The test zone is deliberately non-UTC, exactly as in [UserDataRepositoryImplTest]: the SDK's
 * `LocalDateTime` fields are serialised through `ZoneId.systemDefault()`, so a comparison that
 * treated them as UTC would silently be wrong by the device's offset — which is the bug
 * `SdkDateTime.kt` exists to prevent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserDataSyncerTest {
    private val userDataDao = mockk<UserDataDao>()
    private val apiClient = mockk<ApiClient>()
    private val playStateApi = mockk<PlayStateApi>()
    private val userLibraryApi = mockk<UserLibraryApi>()
    private val itemsApi = mockk<ItemsApi>()
    private val eventBus = UserDataEventBus()

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val itemUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val otherItemUuid = UUID.fromString("33333333-3333-3333-3333-333333333333")

    /** "Now" on the device. Local rows are stamped relative to it. */
    private val now: Instant = Instant.parse("2026-07-29T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val syncer =
        UserDataSyncer(
            userDataDao = userDataDao,
            apiClient = apiClient,
            eventBus = eventBus,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @BeforeEach
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(TEST_ZONE))
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.playStateApi } returns playStateApi
        every { apiClient.userLibraryApi } returns userLibraryApi
        every { apiClient.itemsApi } returns itemsApi

        coEvery { userDataDao.upsert(any()) } just Runs
        coEvery { userDataDao.clearPendingSync(any(), any(), any()) } returns 1
        coEvery { playStateApi.markPlayedItem(any(), any(), any()) } returns userDataResponse()
        coEvery { playStateApi.markUnplayedItem(any(), any()) } returns userDataResponse()
        coEvery { userLibraryApi.markFavoriteItem(any(), any()) } returns userDataResponse()
        coEvery { userLibraryApi.unmarkFavoriteItem(any(), any()) } returns userDataResponse()
        coEvery { itemsApi.updateItemUserData(any(), any(), any()) } returns userDataResponse()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        TimeZone.setDefault(originalTimeZone)
    }

    // ---- nothing to do ---------------------------------------------------------------------

    @Test
    fun `an empty pending list is not a round trip`() =
        runTest {
            coEvery { userDataDao.getPendingSync() } returns emptyList()

            syncer.sync() shouldBe SyncOutcome.NOTHING_PENDING

            coVerify(exactly = 0) { userLibraryApi.getItem(any(), any()) }
        }

    // ---- local newer -----------------------------------------------------------------------

    @Test
    fun `a local change newer than the server's is pushed and stops being pending`() =
        runTest {
            pending(row(playbackPositionTicks = 30_000_000_000L, lastPlayedDate = now))
            serverUserData(lastPlayedDate = now.minusSeconds(3_600))

            syncer.sync() shouldBe SyncOutcome.DRAINED

            coVerify(exactly = 1) { itemsApi.updateItemUserData(itemUuid, userId, any()) }
            coVerify(exactly = 1) { userDataDao.clearPendingSync(itemUuid, userId, now) }
            coVerify(exactly = 0) { userDataDao.upsert(any()) }
        }

    @Test
    fun `the push carries the whole row, position included`() =
        runTest {
            pending(
                row(
                    played = false,
                    isFavorite = true,
                    playbackPositionTicks = 30_000_000_000L,
                    lastPlayedDate = now,
                ),
            )
            serverUserData(lastPlayedDate = null)
            val pushed = slot<UpdateUserItemDataDto>()
            coEvery { itemsApi.updateItemUserData(any(), any(), capture(pushed)) } returns userDataResponse()

            syncer.sync()

            pushed.captured.playbackPositionTicks shouldBe 30_000_000_000L
            pushed.captured.isFavorite shouldBe true
            pushed.captured.played shouldBe false
            pushed.captured.lastPlayedDate.shouldNotBeNull()
        }

    @Test
    fun `the dedicated endpoints go first, so the position is not cleared behind them`() =
        runTest {
            pending(row(played = true, isFavorite = true, lastPlayedDate = now))
            serverUserData(lastPlayedDate = null)

            syncer.sync()

            // markPlayedItem clears the server's resume position, so updateItemUserData has to be
            // the last word — otherwise every watched item would come back with position 0.
            coVerifyOrder {
                playStateApi.markPlayedItem(any(), any(), any())
                userLibraryApi.markFavoriteItem(any(), any())
                itemsApi.updateItemUserData(any(), any(), any())
            }
        }

    @Test
    fun `an unwatched, unfavourited row uses the negative endpoints`() =
        runTest {
            pending(row(played = false, isFavorite = false, lastPlayedDate = now))
            serverUserData(lastPlayedDate = null)

            syncer.sync()

            coVerify(exactly = 1) { playStateApi.markUnplayedItem(itemUuid, userId) }
            coVerify(exactly = 1) { userLibraryApi.unmarkFavoriteItem(itemUuid, userId) }
            coVerify(exactly = 0) { playStateApi.markPlayedItem(any(), any(), any()) }
        }

    @Test
    fun `the pushed played date resolves back to the instant that was stored`() =
        runTest {
            // Regression guard for the M4/M6 timezone bug: the SDK stamps the device's offset onto
            // whatever wall-clock time it is given.
            pending(row(played = true, lastPlayedDate = now))
            serverUserData(lastPlayedDate = null)
            val sent = slot<LocalDateTime>()
            coEvery { playStateApi.markPlayedItem(any(), any(), capture(sent)) } returns userDataResponse()

            syncer.sync()

            sent.captured shouldBe LocalDateTime.ofInstant(now, ZoneId.of(TEST_ZONE))
            sent.captured.atZone(ZoneId.of(TEST_ZONE)).toInstant() shouldBe now
        }

    @Test
    fun `a server that has never played the item cannot outrank the local change`() =
        runTest {
            pending(row(lastPlayedDate = now))
            serverUserData(lastPlayedDate = null)

            syncer.sync() shouldBe SyncOutcome.DRAINED

            coVerify(exactly = 1) { itemsApi.updateItemUserData(any(), any(), any()) }
            coVerify(exactly = 0) { userDataDao.upsert(any()) }
        }

    @Test
    fun `an item the server reports no user data for is pushed rather than adopted`() =
        runTest {
            pending(row(lastPlayedDate = now))
            coEvery { userLibraryApi.getItem(itemUuid, userId) } returns itemResponse(userData = null)

            syncer.sync() shouldBe SyncOutcome.DRAINED

            coVerify(exactly = 1) { itemsApi.updateItemUserData(any(), any(), any()) }
        }

    // ---- server newer ----------------------------------------------------------------------

    @Test
    fun `a server change newer than the local one is adopted, not overwritten`() =
        runTest {
            pending(row(playbackPositionTicks = 10L, updatedAt = now.minusSeconds(3_600)))
            serverUserData(
                lastPlayedDate = now,
                playbackPositionTicks = 90_000_000_000L,
                played = true,
                isFavorite = true,
            )

            syncer.sync() shouldBe SyncOutcome.DRAINED

            val adopted = slot<UserDataEntity>()
            coVerify(exactly = 1) { userDataDao.upsert(capture(adopted)) }
            adopted.captured.playbackPositionTicks shouldBe 90_000_000_000L
            adopted.captured.played shouldBe true
            adopted.captured.isFavorite shouldBe true
            // The row is a copy of server state now, so it owes the server nothing.
            adopted.captured.toBeSynced shouldBe false
            adopted.captured.lastPlayedDate shouldBe now
            adopted.captured.updatedAt shouldBe now
            coVerify(exactly = 0) { itemsApi.updateItemUserData(any(), any(), any()) }
        }

    @Test
    fun `adopting the server's value repaints the screens through the event bus`() =
        runTest {
            pending(row(updatedAt = now.minusSeconds(3_600)))
            serverUserData(lastPlayedDate = now, playbackPositionTicks = 42L)

            eventBus.changes.test {
                syncer.sync()

                val change = awaitItem()
                change.itemId shouldBe itemUuid.toString()
                change.userData.playbackPositionTicks shouldBe 42L
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a tie goes to the server, because it already holds that state`() =
        runTest {
            pending(row(updatedAt = now))
            serverUserData(lastPlayedDate = now, playbackPositionTicks = 7L)

            syncer.sync() shouldBe SyncOutcome.DRAINED

            coVerify(exactly = 1) { userDataDao.upsert(any()) }
            coVerify(exactly = 0) { itemsApi.updateItemUserData(any(), any(), any()) }
        }

    @Test
    fun `a favourite toggled offline beats a film watched last week`() =
        runTest {
            // `updatedAt`, not the local `lastPlayedDate`: a favourite toggle never touches the
            // latter, so comparing those two would lose the change.
            pending(row(isFavorite = true, lastPlayedDate = now.minusSeconds(604_800), updatedAt = now))
            serverUserData(lastPlayedDate = now.minusSeconds(604_800))

            syncer.sync() shouldBe SyncOutcome.DRAINED

            coVerify(exactly = 1) { userLibraryApi.markFavoriteItem(itemUuid, userId) }
            coVerify(exactly = 0) { userDataDao.upsert(any()) }
        }

    // ---- failures ---------------------------------------------------------------------------

    @Test
    fun `a server that cannot be reached leaves the row pending and asks for a retry`() =
        runTest {
            pending(row(lastPlayedDate = now))
            coEvery { userLibraryApi.getItem(any(), any()) } throws IOException("offline")

            syncer.sync() shouldBe SyncOutcome.RETRY

            coVerify(exactly = 0) { userDataDao.clearPendingSync(any(), any(), any()) }
            coVerify(exactly = 0) { userDataDao.upsert(any()) }
        }

    @Test
    fun `a push that fails mid-way leaves the row pending`() =
        runTest {
            pending(row(lastPlayedDate = now))
            serverUserData(lastPlayedDate = null)
            coEvery { itemsApi.updateItemUserData(any(), any(), any()) } throws IOException("offline")

            syncer.sync() shouldBe SyncOutcome.RETRY

            coVerify(exactly = 0) { userDataDao.clearPendingSync(any(), any(), any()) }
        }

    @Test
    fun `an item that no longer exists on the server is dropped rather than retried forever`() =
        runTest {
            pending(row(lastPlayedDate = now))
            coEvery { userLibraryApi.getItem(any(), any()) } throws InvalidStatusException(status = 404)

            syncer.sync() shouldBe SyncOutcome.DRAINED

            coVerify(exactly = 1) { userDataDao.clearPendingSync(itemUuid, userId, now) }
        }

    @Test
    fun `one unreachable row does not hold back the others`() =
        runTest {
            val good = row(lastPlayedDate = now)
            val bad = row(itemId = otherItemUuid, lastPlayedDate = now)
            coEvery { userDataDao.getPendingSync() } returns listOf(good, bad)
            coEvery { userLibraryApi.getItem(itemUuid, userId) } returns itemResponse(serverData(null))
            coEvery { userLibraryApi.getItem(otherItemUuid, userId) } throws IOException("offline")

            syncer.sync() shouldBe SyncOutcome.RETRY

            // The reachable row is done with; only the failed one comes back next time.
            coVerify(exactly = 1) { userDataDao.clearPendingSync(itemUuid, userId, now) }
            coVerify(exactly = 0) { userDataDao.clearPendingSync(otherItemUuid, any(), any()) }
        }

    @Test
    fun `every pending row is visited, oldest first`() =
        runTest {
            coEvery { userDataDao.getPendingSync() } returns
                listOf(row(lastPlayedDate = now), row(itemId = otherItemUuid, lastPlayedDate = now))
            coEvery { userLibraryApi.getItem(any(), any()) } returns itemResponse(serverData(null))

            syncer.sync() shouldBe SyncOutcome.DRAINED

            coVerifyOrder {
                userLibraryApi.getItem(itemUuid, userId)
                userLibraryApi.getItem(otherItemUuid, userId)
            }
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun pending(vararg rows: UserDataEntity) {
        coEvery { userDataDao.getPendingSync() } returns rows.toList()
    }

    private fun serverUserData(
        lastPlayedDate: Instant?,
        playbackPositionTicks: Long = 0L,
        played: Boolean = false,
        isFavorite: Boolean = false,
    ) {
        coEvery { userLibraryApi.getItem(any(), any()) } returns
            itemResponse(
                serverData(
                    lastPlayedDate = lastPlayedDate,
                    playbackPositionTicks = playbackPositionTicks,
                    played = played,
                    isFavorite = isFavorite,
                ),
            )
    }

    private fun serverData(
        lastPlayedDate: Instant?,
        playbackPositionTicks: Long = 0L,
        played: Boolean = false,
        isFavorite: Boolean = false,
    ) = UserItemDataDto(
        playbackPositionTicks = playbackPositionTicks,
        playCount = 0,
        isFavorite = isFavorite,
        played = played,
        key = "key",
        itemId = itemUuid,
        // Written the way the SDK's zone-aware serializer would have deserialised it.
        lastPlayedDate = lastPlayedDate?.let { LocalDateTime.ofInstant(it, ZoneId.of(TEST_ZONE)) },
    )

    @Suppress("LongParameterList")
    private fun row(
        itemId: UUID = itemUuid,
        played: Boolean = false,
        isFavorite: Boolean = false,
        playbackPositionTicks: Long = 0L,
        lastPlayedDate: Instant? = null,
        updatedAt: Instant = now,
    ) = UserDataEntity(
        itemId = itemId,
        userId = userId,
        played = played,
        isFavorite = isFavorite,
        playbackPositionTicks = playbackPositionTicks,
        lastPlayedDate = lastPlayedDate,
        toBeSynced = true,
        updatedAt = updatedAt,
    )

    private fun itemResponse(userData: UserItemDataDto?) =
        Response(
            content = BaseItemDto(id = itemUuid, type = BaseItemKind.MOVIE, userData = userData),
            status = 200,
            headers = emptyMap(),
        )

    private fun userDataResponse() =
        Response(
            content = serverData(lastPlayedDate = null),
            status = 200,
            headers = emptyMap(),
        )

    private companion object {
        /** A fixed non-UTC zone with a non-zero offset all year round. */
        const val TEST_ZONE = "Europe/Paris"
    }
}
