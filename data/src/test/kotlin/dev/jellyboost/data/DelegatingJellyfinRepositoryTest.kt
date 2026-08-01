package dev.jellyboost.data

import androidx.paging.PagingData
import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.valueParameters

/**
 * Unit tests for [DelegatingJellyfinRepository] — the per-call online/offline decision every
 * screen in the app depends on.
 *
 * This is the densest test class in `:data` on purpose. Getting the matrix wrong is not a visual
 * bug: falling back on a 401 would strand the user in a silently-expired session showing only
 * downloads, and *not* falling back on a transport failure would show an error screen to someone
 * whose downloaded library is sitting right there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelegatingJellyfinRepositoryTest {
    private val online = mockk<OnlineJellyfinRepository>()
    private val offline = mockk<OfflineJellyfinRepository>()
    private val connectionState = mockk<ConnectionStateProvider>(relaxed = true)
    private val state = MutableStateFlow(ConnectionState.ONLINE)

    private val repository = DelegatingJellyfinRepository(online, offline, connectionState)

    private val fromServer = listOf(item("server"))
    private val fromCache = listOf(item("cache"))

    @BeforeEach
    fun setUp() {
        every { connectionState.state } returns state
        coEvery { online.getResumeItems(any()) } returns AppResult.Success(fromServer)
        coEvery { offline.getResumeItems(any()) } returns AppResult.Success(fromCache)
    }

    // ---- which source ------------------------------------------------------------------------

    @Test
    fun `goes to the server while online`() =
        runTest {
            repository.getResumeItems(12).names() shouldContainExactly listOf("server")

            coVerify(exactly = 0) { offline.getResumeItems(any()) }
        }

    @Test
    fun `goes straight to the cache with no network, without touching the server`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            repository.getResumeItems(12).names() shouldContainExactly listOf("cache")

            coVerify(exactly = 0) { online.getResumeItems(any()) }
        }

    @Test
    fun `goes straight to the cache when the server is known to be unreachable`() =
        runTest {
            state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE

            repository.getResumeItems(12).names() shouldContainExactly listOf("cache")

            coVerify(exactly = 0) { online.getResumeItems(any()) }
        }

    @Test
    fun `honours forced offline mode even though the network is fine`() =
        runTest {
            state.value = ConnectionState.OFFLINE_FORCED

            repository.getResumeItems(12).names() shouldContainExactly listOf("cache")

            coVerify(exactly = 0) { online.getResumeItems(any()) }
            // A user choice, not an observation: nothing to report and nothing to re-probe.
            verify(exactly = 0) { connectionState.reportFailure() }
        }

    @Test
    fun `re-reads the connection state per call rather than caching the decision`() =
        runTest {
            repository.getResumeItems(12).names() shouldContainExactly listOf("server")

            state.value = ConnectionState.OFFLINE_NO_NETWORK

            repository.getResumeItems(12).names() shouldContainExactly listOf("cache")
        }

    // ---- failure handling ---------------------------------------------------------------------

    @Test
    fun `a transport failure reports the failure and answers from the cache`() =
        runTest {
            coEvery { online.getResumeItems(any()) } returns
                AppResult.Failure(AppError.Network(IOException("connection reset")))

            repository.getResumeItems(12).names() shouldContainExactly listOf("cache")

            verify(exactly = 1) { connectionState.reportFailure() }
        }

    @Test
    fun `a 503 from a proxy in front of a stopped server falls back like any transport failure`() =
        runTest {
            coEvery { online.getResumeItems(any()) } returns
                AppResult.Failure(AppError.Server(statusCode = 503))

            repository.getResumeItems(12).names() shouldContainExactly listOf("cache")

            verify(exactly = 1) { connectionState.reportFailure() }
        }

    @Test
    fun `502 and 504 fall back too`() =
        runTest {
            listOf(502, 504).forEach { status ->
                coEvery { online.getResumeItems(any()) } returns
                    AppResult.Failure(AppError.Server(statusCode = status))

                repository.getResumeItems(12).names() shouldContainExactly listOf("cache")
            }
        }

    @Test
    fun `a 401 is surfaced untouched so the session layer can re-authenticate`() =
        runTest {
            val unauthorized = AppResult.Failure(AppError.Unauthorized())
            coEvery { online.getResumeItems(any()) } returns unauthorized

            val result = repository.getResumeItems(12)

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Unauthorized>()
            // Swallowing this into a cache read would hide an expired session behind stale content.
            coVerify(exactly = 0) { offline.getResumeItems(any()) }
            verify(exactly = 0) { connectionState.reportFailure() }
        }

    @Test
    fun `a 500 is a server bug, not an unreachable server, and is surfaced`() =
        runTest {
            coEvery { online.getResumeItems(any()) } returns
                AppResult.Failure(AppError.Server(statusCode = 500))

            val result = repository.getResumeItems(12)

            result.shouldBeInstanceOf<AppResult.Failure>()
            coVerify(exactly = 0) { offline.getResumeItems(any()) }
        }

    @Test
    fun `a 404 is surfaced rather than answered from a stale cache`() =
        runTest {
            coEvery { online.getResumeItems(any()) } returns AppResult.Failure(AppError.NotFound("x"))

            repository.getResumeItems(12).shouldBeInstanceOf<AppResult.Failure>()
            coVerify(exactly = 0) { offline.getResumeItems(any()) }
        }

    @Test
    fun `a server call that never answers is cut off and served from the cache`() =
        runTest {
            coEvery { online.getResumeItems(any()) } coAnswers {
                // What a dead server behind a live Wi-Fi looks like before the SDK's own timeout.
                delay(SOCKET_TIMEOUT_MS)
                AppResult.Success(fromServer)
            }

            val start = testScheduler.currentTime
            repository.getResumeItems(12).names() shouldContainExactly listOf("cache")

            (testScheduler.currentTime - start) shouldBe
                JellyfinRepository.ONLINE_CALL_TIMEOUT_MS
            verify(exactly = 1) { connectionState.reportFailure() }
        }

    // ---- the whole surface --------------------------------------------------------------------

    @Test
    fun `every call on the interface is delegated, not just the one the matrix is written against`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            coEvery { offline.getUserViews() } returns AppResult.Success(emptyList())
            coEvery { offline.getNextUp(any()) } returns AppResult.Success(fromCache)
            coEvery { offline.getLatestMedia(any(), any()) } returns AppResult.Success(fromCache)
            coEvery { offline.getItem(any()) } returns AppResult.Success(item("cache"))
            coEvery { offline.getSeasons(any()) } returns AppResult.Success(fromCache)
            coEvery { offline.getEpisodes(any(), any()) } returns AppResult.Success(fromCache)
            coEvery { offline.getNextUpForSeries(any()) } returns AppResult.Success(null)
            coEvery { offline.getSimilarItems(any(), any()) } returns AppResult.Success(emptyList())
            coEvery { offline.getItems(any()) } returns AppResult.Success(fromCache)
            coEvery { offline.getFilterFacets(any(), any()) } returns
                AppResult.Success(
                    dev.jellyboost.core.common.model
                        .FilterFacets(),
                )

            repository.getUserViews().shouldBeInstanceOf<AppResult.Success<*>>()
            repository.getNextUp(24).names() shouldContainExactly listOf("cache")
            repository.getLatestMedia("library", 16).names() shouldContainExactly listOf("cache")
            repository.getItem("id").shouldBeInstanceOf<AppResult.Success<*>>()
            repository.getSeasons("series").names() shouldContainExactly listOf("cache")
            repository.getEpisodes("series", "season").names() shouldContainExactly listOf("cache")
            repository.getNextUpForSeries("series").shouldBeInstanceOf<AppResult.Success<*>>()
            repository.getSimilarItems("id", 12).shouldBeInstanceOf<AppResult.Success<*>>()
            repository.getItems(ItemQuery()).names() shouldContainExactly listOf("cache")
            repository.getFilterFacets(null, listOf(ItemType.MOVIE)).shouldBeInstanceOf<AppResult.Success<*>>()

            coVerify(exactly = 0) { online.getUserViews() }
        }

    /**
     * The same guarantee as the test above, but derived from the interface instead of typed out.
     *
     * The hand-written list is worth keeping — it asserts the *values* come back, not just that
     * something was called — but it is only ever as complete as whoever last extended
     * [JellyfinRepository] remembered to make it, and it was not: `getResumeItems` was missing for
     * three milestones (audit ARCH-09). This walks every member the interface declares, calls it
     * reflectively on the delegate, and demands the identically-named member fire on the offline
     * implementation. A member added tomorrow and forgotten in `DelegatingJellyfinRepository`
     * cannot compile at all (Kotlin requires the override); a member added and *stubbed out* —
     * `TODO()`, `AppResult.Success(emptyList())`, a delegate to the wrong side — fails here.
     *
     * [sampleFor] deliberately throws on a parameter type it has never seen, so a new parameter
     * shape is a loud failure asking for one line rather than a silently skipped member.
     */
    @Test
    fun `every member the interface declares reaches a delegate, by reflection over the interface`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            val members = JellyfinRepository::class.declaredMemberFunctions
            // Guards the whole test against a reflection change that hands back nothing to walk.
            // A floor rather than an exact count: adding a member must fail *routing*, not this.
            members.size shouldBeGreaterThan MINIMUM_INTERFACE_MEMBERS

            members.forEach { member ->
                val arguments = member.valueParameters.map { sampleFor(it.type) }.toTypedArray()

                if (member.isSuspend) {
                    coEvery { member.callSuspend(offline, *arguments) } returns AppResult.Success(Unit)
                    member.callSuspend(repository, *arguments)
                    coVerify(exactly = 1) { member.callSuspend(offline, *arguments) }
                } else {
                    every { member.call(offline, *arguments) } returns flowOf(PagingData.from(fromCache))
                    @Suppress("UNCHECKED_CAST")
                    (member.call(repository, *arguments) as Flow<Any?>).first()
                    verify(exactly = 1) { member.call(offline, *arguments) }
                }
            }
        }

    /**
     * A value to call a repository member with, chosen only so the call is well-formed.
     *
     * Nothing asserts on these: the question this test asks is *where the call went*, and MockK
     * matches the same constants on the way in and on the way out.
     */
    private fun sampleFor(type: KType): Any =
        when (type.classifier) {
            Int::class -> 1
            String::class -> "id"
            List::class -> listOf(ItemType.MOVIE)
            ItemQuery::class -> ItemQuery()
            // `getItemsPaged`'s total-count callback: erased to Function1 through reflection.
            Function1::class -> { _: Any? -> }
            else -> error("No sample value for $type — add one so this member is really exercised")
        }

    // ---- the paged grid -----------------------------------------------------------------------

    @Test
    fun `the paged grid reads the server while online`() =
        runTest {
            every { online.getItemsPaged(any(), any()) } returns flowOf(PagingData.from(fromServer))
            every { offline.getItemsPaged(any(), any()) } returns flowOf(PagingData.from(fromCache))

            repository.getItemsPaged(ItemQuery()).first()

            verify(exactly = 1) { online.getItemsPaged(any(), any()) }
            verify(exactly = 0) { offline.getItemsPaged(any(), any()) }
        }

    @Test
    fun `the paged grid swaps to downloaded items when the connection drops mid-scroll`() =
        runTest {
            every { online.getItemsPaged(any(), any()) } returns flowOf(PagingData.from(fromServer))
            every { offline.getItemsPaged(any(), any()) } returns flowOf(PagingData.from(fromCache))

            // The grid's source is re-chosen on every connection change, so a subscription started
            // online keeps working — against Room — once the network goes away.
            repository.getItemsPaged(ItemQuery()).test {
                awaitItem()
                verify(exactly = 1) { online.getItemsPaged(any(), any()) }

                state.value = ConnectionState.OFFLINE_NO_NETWORK

                awaitItem()
                verify(exactly = 1) { offline.getItemsPaged(any(), any()) }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the paged grid is not rebuilt for a change between two offline reasons`() =
        runTest {
            every { offline.getItemsPaged(any(), any()) } returns flowOf(PagingData.from(fromCache))
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            repository.getItemsPaged(ItemQuery()).test {
                awaitItem()

                // Still offline, just for a different reason — re-creating the Pager here would
                // throw the user back to the top of the grid for nothing.
                state.value = ConnectionState.OFFLINE_FORCED

                expectNoEvents()
                verify(exactly = 1) { offline.getItemsPaged(any(), any()) }
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- helpers ------------------------------------------------------------------------------

    private fun AppResult<List<JellyfinItem>>.names(): List<String> = (this as AppResult.Success).value.map { it.name }

    private fun item(name: String) = JellyfinItem(id = name, name = name, type = ItemType.MOVIE)

    private companion object {
        /** The SDK's own default socket timeout — the number M6's definition of done rules out. */
        const val SOCKET_TIMEOUT_MS = 30_000L

        /**
         * Below the surface the interface has ever had, so the structural walk cannot pass on an
         * empty member list, and above nothing that a real extension would trip over.
         */
        const val MINIMUM_INTERFACE_MEMBERS = 10
    }
}
