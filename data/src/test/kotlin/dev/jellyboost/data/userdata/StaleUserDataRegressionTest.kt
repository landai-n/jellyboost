package dev.jellyboost.data.userdata

import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.BrowseCacheWriter
import dev.jellyboost.data.cache.CacheFixtures
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Regression test for STATUS.md's "**Stale local user-data rows corrupt server state on
 * playback**".
 *
 * The bug needed two halves to bite, so this test uses both of them against one shared `user_data`
 * store: [BrowseCacheWriter] (the read path) and [UserDataRepositoryImpl] (the write path).
 *
 * Observed on the device: an item was marked played in-app (row `played = true`,
 * `toBeSynced = false`), later unmarked from jellyfin-web (server `Played = false`), and playing it
 * in the app re-marked it played on the server — because `setPosition` pushes the item's *full*
 * desired state built from a local row that no read had ever refreshed.
 *
 * The rule this pins: **a server read is authoritative for an item unless a local write is still
 * waiting to be pushed.**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleUserDataRegressionTest {
    private val itemDao = mockk<ItemDao>()
    private val libraryViewDao = mockk<LibraryViewDao>()
    private val userDataDao = mockk<UserDataDao>()
    private val sessionRepository = mockk<SessionRepository>()
    private val apiClient = mockk<ApiClient>()
    private val itemsApi = mockk<ItemsApi>()
    private val syncScheduler = mockk<UserDataSyncScheduler>(relaxUnitFun = true)
    private val connectionState = mockk<ConnectionStateProvider>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    /** The `user_data` table, in memory: both halves of the fix read and write the same rows. */
    private val stored = mutableMapOf<UUID, UserDataEntity>()

    private val pushed = slot<UpdateUserItemDataDto>()

    @BeforeEach
    fun setUp() {
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.itemsApi } returns itemsApi
        coEvery { itemsApi.updateItemUserData(any(), any(), capture(pushed)) } returns userDataResponse()

        every { sessionRepository.sessionState } returns
            MutableStateFlow(
                SessionState.LoggedIn(
                    serverId = UUID.randomUUID(),
                    userId = USER_ID,
                    userName = "casey",
                    serverName = "home",
                    serverVersion = "10.11.0",
                ),
            )

        // This regression is about what a push carries, so the repository is exercised online.
        every { connectionState.state } returns MutableStateFlow(ConnectionState.ONLINE)

        coEvery { itemDao.getCacheKeys(any()) } returns emptyList()
        coEvery { itemDao.upsert(any()) } just Runs
        coEvery { libraryViewDao.upsert(any()) } just Runs

        coEvery { userDataDao.getUserData(any(), any()) } answers { stored[firstArg<UUID>()] }
        coEvery { userDataDao.upsert(any<UserDataEntity>()) } answers {
            firstArg<UserDataEntity>().let { stored[it.itemId] = it }
        }
        coEvery { userDataDao.upsertAll(any()) } answers {
            firstArg<List<UserDataEntity>>().forEach { stored[it.itemId] = it }
        }
        coEvery { userDataDao.getPendingSyncIds(any(), any()) } answers {
            firstArg<List<UUID>>().filter { stored[it]?.toBeSynced == true }
        }
        coEvery { userDataDao.clearPendingSync(any(), any(), any()) } answers {
            val row = stored[firstArg<UUID>()]
            if (row != null && !row.updatedAt.isAfter(thirdArg())) {
                stored[row.itemId] = row.copy(toBeSynced = false)
                1
            } else {
                0
            }
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a server read corrects a stale row, so the next position write stops resurrecting it`() =
        runTest {
            // The device's row from an old in-app "mark played", long since pushed.
            stored[ITEM_ID] = row(played = true, toBeSynced = false)

            // The user then unmarked the item in jellyfin-web; the next read in the app says so.
            writer(this).writeItems(listOf(movieWithUserData(played = false)))

            stored.getValue(ITEM_ID).played shouldBe false

            // Five seconds into playback the player records a position.
            repository().setPosition(ITEM_ID.toString(), positionTicks = 50_000_000L)

            // The full-state push now carries the server's own truth instead of the stale row's.
            pushed.captured.played shouldBe false
            pushed.captured.playbackPositionTicks shouldBe 50_000_000L
        }

    @Test
    fun `a favourite the server has not accepted yet survives a read and is still pushed`() =
        runTest {
            // A local favourite write that never reached the server (offline when it was made).
            stored[ITEM_ID] = row(isFavorite = true, toBeSynced = true)

            // A read whose response predates it must not roll it back.
            writer(this).writeItems(listOf(movieWithUserData(played = false, isFavorite = false)))

            stored.getValue(ITEM_ID).isFavorite shouldBe true
            stored.getValue(ITEM_ID).toBeSynced shouldBe true

            repository().setPosition(ITEM_ID.toString(), positionTicks = 50_000_000L)

            pushed.captured.isFavorite shouldBe true
        }

    // ---- collaborators --------------------------------------------------------------------------

    private fun writer(scope: TestScope) =
        BrowseCacheWriter(
            itemDao = itemDao,
            libraryViewDao = libraryViewDao,
            userDataDao = userDataDao,
            sessionRepository = sessionRepository,
            mapper = CacheFixtures.mapper,
            clock = clock,
            scope = scope,
        )

    private fun repository() =
        UserDataRepositoryImpl(
            userDataDao = userDataDao,
            apiClient = apiClient,
            sessionRepository = sessionRepository,
            eventBus = UserDataEventBus(),
            syncScheduler = syncScheduler,
            connectionState = connectionState,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun row(
        played: Boolean = false,
        isFavorite: Boolean = false,
        toBeSynced: Boolean,
    ) = UserDataEntity(
        itemId = ITEM_ID,
        userId = USER_ID,
        played = played,
        isFavorite = isFavorite,
        toBeSynced = toBeSynced,
        updatedAt = NOW.minusSeconds(86_400),
    )

    private fun movieWithUserData(
        played: Boolean,
        isFavorite: Boolean = false,
    ): BaseItemDto =
        BaseItemDto(
            id = ITEM_ID,
            type = BaseItemKind.MOVIE,
            name = "Citizen Vigilante",
            userData =
                UserItemDataDto(
                    playbackPositionTicks = 0L,
                    playCount = 0,
                    isFavorite = isFavorite,
                    played = played,
                    key = ITEM_ID.toString(),
                    itemId = ITEM_ID,
                ),
        )

    private fun userDataResponse() =
        Response(
            content =
                UserItemDataDto(
                    playbackPositionTicks = 0L,
                    playCount = 0,
                    isFavorite = false,
                    played = false,
                    key = ITEM_ID.toString(),
                    itemId = ITEM_ID,
                ),
            status = 200,
            headers = emptyMap(),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-28T10:00:00Z")
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ITEM_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
