package dev.jellyfinnative.data.homelayout

import dev.jellyfinnative.core.common.model.HomeSectionType
import dev.jellyfinnative.core.datastore.HomeLayoutStore
import dev.jellyfinnative.data.ConnectivityRefresher
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.operations.DisplayPreferencesApi
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ScrollDirection
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [HomeLayoutRepository].
 *
 * The contract under test is "never fails, always answers with something renderable": every branch
 * here ends in a usable list of sections.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLayoutRepositoryTest {
    private val apiClient = mockk<ApiClient>()
    private val displayPreferencesApi = mockk<DisplayPreferencesApi>()
    private val store = mockk<HomeLayoutStore>(relaxed = true)

    private var online = true
    private val connectivity =
        mockk<ConnectivityRefresher> {
            every { isOnline } answers { online }
        }

    private val repository =
        HomeLayoutRepository(
            apiClient = apiClient,
            store = store,
            connectivity = connectivity,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    @BeforeEach
    fun setUp() {
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.displayPreferencesApi } returns displayPreferencesApi
        every { store.read() } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `reads the layout jellyfin-web wrote and persists it`() =
        runTest {
            stubServer(
                mapOf(
                    "homesection0" to "resume",
                    "homesection1" to "latestmedia",
                    "homesection2" to "none",
                    "homesection3" to "none",
                    "homesection4" to "none",
                    "homesection5" to "none",
                    "homesection6" to "none",
                    "homesection7" to "none",
                    "homesection8" to "none",
                    "homesection9" to "none",
                ),
            )

            val sections = repository.getHomeSections()

            sections shouldContainExactly listOf(HomeSectionType.RESUME, HomeSectionType.LATEST_MEDIA)
            coVerify(exactly = 1) { store.write(sections) }
        }

    @Test
    fun `asks for the record jellyfin-web actually writes`() =
        runTest {
            val id = slot<String>()
            val client = slot<String>()
            coEvery {
                displayPreferencesApi.getDisplayPreferences(capture(id), any(), capture(client))
            } returns Response(content = displayPreferences(emptyMap()), status = 200, headers = emptyMap())

            repository.getHomeSections()

            // Both strings are load-bearing: preferences are partitioned by
            // (userId, itemId, client), so anything else reads an unrelated — and empty — record.
            id.captured shouldBe "usersettings"
            client.captured shouldBe "emby"
        }

    @Test
    fun `an empty record means the defaults, not an empty home screen`() =
        runTest {
            stubServer(emptyMap())

            repository.getHomeSections() shouldContainExactly DEFAULT_HOME_SECTIONS
        }

    @Test
    fun `a failed fetch falls back to the last layout seen`() =
        runTest {
            val persisted = listOf(HomeSectionType.NEXT_UP, HomeSectionType.RESUME)
            every { store.read() } returns persisted
            coEvery { displayPreferencesApi.getDisplayPreferences(any(), any(), any()) } throws
                TimeoutException("timed out")

            repository.getHomeSections() shouldContainExactly persisted
            // Nothing was learned, so nothing is written — the cache keeps what it had.
            coVerify(exactly = 0) { store.write(any()) }
        }

    @Test
    fun `a failed fetch with nothing cached still renders the defaults`() =
        runTest {
            coEvery { displayPreferencesApi.getDisplayPreferences(any(), any(), any()) } throws
                TimeoutException("timed out")

            repository.getHomeSections() shouldContainExactly DEFAULT_HOME_SECTIONS
        }

    @Test
    fun `offline the layout comes from the cache without a request`() =
        runTest {
            online = false
            val persisted = listOf(HomeSectionType.LATEST_MEDIA)
            every { store.read() } returns persisted

            repository.getHomeSections() shouldContainExactly persisted

            coVerify(exactly = 0) { displayPreferencesApi.getDisplayPreferences(any(), any(), any()) }
        }

    @Test
    fun `offline on a fresh install falls back to the defaults`() =
        runTest {
            online = false

            repository.getHomeSections() shouldContainExactly DEFAULT_HOME_SECTIONS
        }

    private fun stubServer(customPrefs: Map<String, String>) {
        coEvery { displayPreferencesApi.getDisplayPreferences(any(), any(), any()) } returns
            Response(content = displayPreferences(customPrefs), status = 200, headers = emptyMap())
    }

    private fun displayPreferences(customPrefs: Map<String, String>) =
        DisplayPreferencesDto(
            id = "usersettings",
            client = "emby",
            customPrefs = customPrefs,
            rememberIndexing = false,
            primaryImageHeight = 0,
            primaryImageWidth = 0,
            scrollDirection = ScrollDirection.HORIZONTAL,
            showBackdrop = false,
            rememberSorting = false,
            sortOrder = SortOrder.ASCENDING,
            showSidebar = false,
        )
}
