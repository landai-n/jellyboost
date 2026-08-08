package dev.jellyboost.feature.detail

import androidx.lifecycle.SavedStateHandle
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.userdata.UserDataChange
import dev.jellyboost.data.userdata.UserDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The collaborators an [ItemDetailViewModel] needs, and the builder that assembles one.
 *
 * A base class rather than a helper object because every test in the package reaches the same six
 * mocks by name and overrides one or two of them — the same shape as `PlayerViewModelFixture`
 * (`player/src/test/.../ui/PlayerViewModelFixture.kt`), which is the in-repo model this one
 * mirrors. The four subclasses ([ItemDetailViewModelTest], [ItemDetailSelectionTest],
 * [ItemDetailDownloadTest], [ItemDetailGroupActionsTest]) exist to stay under detekt's
 * `LargeClass` ceiling; before this fixture they rebuilt the same doubles four times over,
 * drifting a little more each time.
 *
 * The base [setUp] stubs only what every one of the four needs. `getEpisodes` and
 * `getSeriesEpisodes` are deliberately **not** here: [ItemDetailSelectionTest] omits the former
 * entirely (its own `givenSeasonWithEpisodes` stubs the exact series/item pair instead), and only
 * [ItemDetailGroupActionsTest] needs the latter. Each subclass that wants either adds its own
 * `@BeforeEach`, which JUnit5 runs after this one — homogenising those stubs across all four would
 * hide the exact drift this fixture exists to stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class ItemDetailViewModelFixture {
    protected val dispatcher = StandardTestDispatcher()
    protected val repository = mockk<JellyfinRepository>()
    protected val userDataRepository = mockk<UserDataRepository>()
    protected val changes =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The badge source (M7); emits an empty map unless a test says otherwise. */
    protected val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** The on-device footprint of [ITEM_ID]; `null` unless a test says otherwise. */
    protected val bytesOnDisk = MutableStateFlow<Long?>(null)
    protected val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
            every { observeBytesOnDisk(any()) } returns bytesOnDisk
        }

    /** The connectivity-change signal (M9); fires only when a test says the server came back. */
    protected val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    protected val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
        }

    /**
     * The group this device is in; `null` until a test joins one (M11 Phase 4).
     *
     * Only [ItemDetailGroupActionsTest] ever writes to this. Everywhere else it stays `null` for
     * the life of the test, which is the point: SyncPlay's arrival must change nothing about an
     * ordinary detail page. The group actions themselves live in [ItemDetailGroupActionsTest].
     */
    protected val activeGroup = MutableStateFlow<SyncPlayGroupHandle?>(null)
    protected val syncPlaySession =
        mockk<SyncPlaySession>(relaxed = true) {
            every { activeGroup } returns this@ItemDetailViewModelFixture.activeGroup
        }

    protected val movie =
        JellyfinItem(id = ITEM_ID, name = "Arrival", type = ItemType.MOVIE, productionYear = 2016)
    protected val series = JellyfinItem(id = ITEM_ID, name = "Westworld", type = ItemType.SERIES)
    protected val season =
        JellyfinItem(id = ITEM_ID, name = "Season 1", type = ItemType.SEASON, seriesId = SERIES_ID)

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(dispatcher)

    @BeforeEach
    fun setUp() {
        every { userDataRepository.changes } returns changes
        coEvery { repository.getSeasons(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getNextUpForSeries(any()) } returns AppResult.Success(null)
        coEvery { repository.getSimilarItems(any(), any()) } returns AppResult.Success(emptyList())
    }

    protected fun viewModel() =
        ItemDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            downloads = downloads,
            connectivityRefresher = connectivityRefresher,
            syncPlaySession = syncPlaySession,
            savedStateHandle = SavedStateHandle(mapOf(ItemDetailViewModel.ARG_ITEM_ID to ITEM_ID)),
        )

    /**
     * The season page as the user reaches it: two episodes, loaded from its series.
     *
     * Shared by [ItemDetailSelectionTest] and [ItemDetailDownloadTest], byte-identical in both
     * before this fixture existed.
     */
    protected fun givenSeasonWithEpisodes() {
        coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(season)
        coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns
            AppResult.Success(
                listOf(
                    JellyfinItem(id = EPISODE_1, name = "The Original", type = ItemType.EPISODE),
                    JellyfinItem(id = EPISODE_2, name = "Chestnut", type = ItemType.EPISODE),
                ),
            )
    }

    protected companion object {
        const val ITEM_ID = "item-1"
        const val SERIES_ID = "series-1"
        const val EPISODE_1 = "episode-1"
        const val EPISODE_2 = "episode-2"
    }
}
