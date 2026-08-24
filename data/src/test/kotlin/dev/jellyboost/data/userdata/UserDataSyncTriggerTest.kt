package dev.jellyboost.data.userdata

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The two moments nothing else can enqueue the drain: a cold start with rows pending, and
 * connectivity coming back — airplane-mode positions reach the server only via the second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserDataSyncTriggerTest {
    private val connectionState = mockk<ConnectionStateProvider>()
    private val state = MutableStateFlow(ConnectionState.ONLINE)
    private val userDataDao = mockk<UserDataDao>()
    private val scheduler = mockk<UserDataSyncScheduler>(relaxUnitFun = true)

    @BeforeEach
    fun setUp() {
        every { connectionState.state } returns state
        coEvery { userDataDao.countPendingSync() } returns 0
    }

    @Test
    fun `an app start with pending rows schedules a drain`() =
        runTest {
            coEvery { userDataDao.countPendingSync() } returns 3

            trigger().start()
            runCurrent()

            // The state flow replays its current value, so the first collection *is* the app-start
            // check.
            verify(exactly = 1) { scheduler.enqueue() }
        }

    @Test
    fun `an app start with nothing pending schedules nothing`() =
        runTest {
            trigger().start()
            runCurrent()

            verify(exactly = 0) { scheduler.enqueue() }
        }

    @Test
    fun `coming back online with pending rows schedules a drain`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            trigger().start()
            runCurrent()
            coEvery { userDataDao.countPendingSync() } returns 1

            state.value = ConnectionState.ONLINE
            runCurrent()

            verify(exactly = 1) { scheduler.enqueue() }
        }

    @Test
    fun `going offline schedules nothing`() =
        runTest {
            coEvery { userDataDao.countPendingSync() } returns 1
            trigger().start()
            runCurrent()

            state.value = ConnectionState.OFFLINE_FORCED
            runCurrent()

            verify(exactly = 1) { scheduler.enqueue() }
        }

    @Test
    fun `swapping between two offline reasons does not re-trigger`() =
        runTest {
            coEvery { userDataDao.countPendingSync() } returns 1
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            trigger().start()
            runCurrent()

            state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
            runCurrent()

            verify(exactly = 0) { scheduler.enqueue() }
        }

    @Test
    fun `starting twice does not double the watch`() =
        runTest {
            coEvery { userDataDao.countPendingSync() } returns 1
            val trigger = trigger()

            trigger.start()
            trigger.start()
            runCurrent()

            verify(exactly = 1) { scheduler.enqueue() }
        }

    @Test
    fun `a failing count is not allowed to bring the app down at startup`() =
        runTest {
            coEvery { userDataDao.countPendingSync() } throws SQLiteException("disk")

            trigger().start()
            runCurrent()

            verify(exactly = 0) { scheduler.enqueue() }
        }

    /**
     * A cancelled application scope arrives here as a `CancellationException`; treating it as "could
     * not count" logs a warning for an ordinary shutdown and runs on inside a cancelled coroutine.
     */
    @Test
    fun `a cancelled count propagates rather than being logged as a failure`() =
        runTest {
            coEvery { userDataDao.countPendingSync() } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { trigger().enqueueIfPending() }

            verify(exactly = 0) { scheduler.enqueue() }
        }

    /**
     * The trigger collects a never-completing `StateFlow`, so it gets [TestScope.backgroundScope] —
     * cancelled when the test ends instead of keeping the test coroutine alive forever.
     */
    private fun TestScope.trigger() =
        UserDataSyncTrigger(
            connectionState = connectionState,
            userDataDao = userDataDao,
            scheduler = scheduler,
            scope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
}
