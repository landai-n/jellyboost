package dev.jellyboost.feature.music

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [MusicLibraryViewModel]'s three paged queries. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()

    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
        }

    private val queries = mutableListOf<ItemQuery>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.getItemsPaged(capture(queries), any()) } returns
            flowOf(PagingData.from(listOf(item("i1", "Item"))))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `takes the library name and starts on the Albums tab`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.uiState.value.libraryName shouldBe "Music"
            viewModel.uiState.value.selectedTab shouldBe MusicLibraryTab.ALBUMS
        }

    @Test
    fun `selecting a tab updates the ui state`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.selectTab(MusicLibraryTab.ARTISTS)

            viewModel.uiState.value.selectedTab shouldBe MusicLibraryTab.ARTISTS
        }

    // Each of the three `val`s (`albums`/`artists`/`playlists`) builds its query in a property
    // initializer, so `viewModel()` alone captures all three into `queries` — one call each, up
    // front — regardless of which flow a test goes on to collect. Tests below look the right query
    // up by the type it asked for rather than assuming there is only one in the list.

    @Test
    fun `albums are scoped to the library and only ask for MUSIC_ALBUM`() =
        runTest(dispatcher) {
            collecting(viewModel().albums) {
                val query = queryFor(ItemType.MUSIC_ALBUM)
                query.parentId shouldBe LIBRARY_ID
                query.itemTypes shouldContainExactly listOf(ItemType.MUSIC_ALBUM)
                query.recursive shouldBe true
                query.sortBy shouldBe SortBy.SORT_NAME
            }
        }

    @Test
    fun `artists are scoped to the library and only ask for MUSIC_ARTIST`() =
        runTest(dispatcher) {
            collecting(viewModel().artists) {
                val query = queryFor(ItemType.MUSIC_ARTIST)
                query.parentId shouldBe LIBRARY_ID
                query.itemTypes shouldContainExactly listOf(ItemType.MUSIC_ARTIST)
                query.recursive shouldBe true
            }
        }

    /**
     * Playlists live outside their music library's own folder on a Jellyfin server, so the query
     * deliberately carries no `parentId` — see [MusicLibraryViewModel]'s KDoc.
     */
    @Test
    fun `playlists carry no parentId, unlike albums and artists`() =
        runTest(dispatcher) {
            collecting(viewModel().playlists) {
                val query = queryFor(ItemType.PLAYLIST)
                query.parentId shouldBe null
                query.itemTypes shouldContainExactly listOf(ItemType.PLAYLIST)
                query.recursive shouldBe true
            }
        }

    private fun queryFor(itemType: ItemType): ItemQuery = queries.first { it.itemTypes.contains(itemType) }

    @Test
    fun `each tab is its own pager, so all three can be collected independently`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collecting(viewModel.albums) {
                collecting(viewModel.artists) {
                    collecting(viewModel.playlists) {
                        // Three collectors, three requests — one per tab's own `Pager`, all alive
                        // regardless of which tab `uiState.selectedTab` currently names.
                        queries.map { it.itemTypes.single() } shouldContainExactly
                            listOf(ItemType.MUSIC_ALBUM, ItemType.MUSIC_ARTIST, ItemType.PLAYLIST)
                    }
                }
            }
        }

    private fun viewModel() =
        MusicLibraryViewModel(
            repository = repository,
            downloads = downloads,
            savedStateHandle =
                SavedStateHandle(mapOf("libraryId" to LIBRARY_ID, "libraryName" to "Music")),
        )

    private fun TestScope.collecting(
        flow: Flow<PagingData<JellyfinItem>>,
        block: TestScope.() -> Unit,
    ) {
        val collection = launch { flow.collect { } }
        advanceUntilIdle()
        try {
            block()
        } finally {
            collection.cancel()
        }
    }

    private fun item(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MUSIC_ALBUM)

    private companion object {
        const val LIBRARY_ID = "lib-music"
    }
}
