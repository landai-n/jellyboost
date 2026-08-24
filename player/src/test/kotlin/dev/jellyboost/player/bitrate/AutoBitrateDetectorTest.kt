package dev.jellyboost.player.bitrate

import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.player.api.PlayerApi
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

// Time is injected rather than read from the clock so the ramp, the early exit and the cache TTL
// can all be driven exactly.
@OptIn(ExperimentalCoroutinesApi::class)
class AutoBitrateDetectorTest {
    private val api = mockk<PlayerApi>()
    private val preferences =
        mockk<AppPreferences> {
            every { maxStreamingBitrate } returns flowOf(null)
            coEvery { setMaxStreamingBitrate(any()) } just Runs
        }

    private var clock = 0L

    // Real elapsed time and the test scheduler's virtual time are deliberately kept apart: the
    // detector's budget runs on the scheduler (so a test can make it expire with a `delay`), while
    // its throughput arithmetic runs on this clock.
    private fun answerChunks(vararg millisPerChunk: Long) {
        var call = 0
        coEvery { api.getBitrateTestBytes(any()) } answers {
            clock += millisPerChunk[call.coerceAtMost(millisPerChunk.lastIndex)]
            call++
            ByteArray(firstArg())
        }
    }

    private fun TestScope.detector() =
        AutoBitrateDetector(
            api = api,
            preferences = preferences,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            now = { clock },
        )

    // ---- the ramp -------------------------------------------------------------------------------

    @Test
    fun `the cap is the whole ramp's cumulative rate with a fifth taken off`() =
        runTest {
            // 500 KB in 200 ms, 1 MB in 400 ms, then 3 MB in 800 ms.
            answerChunks(200L, 400L, 800L)

            val cap = detector().currentCap()

            // Cumulative, not last-chunk: 4_500_000 bytes × 8 ÷ 1.4 s ≈ 25.71 Mbps, minus the 20%
            // headroom. The last chunk alone would read 30 Mbps — TCP's window, not the link.
            cap shouldBe 20_571_428
            coVerify(exactly = 3) { api.getBitrateTestBytes(any()) }
        }

    @Test
    fun `a chunk that takes over a second ends the ramp there`() =
        runTest {
            answerChunks(1_500L)

            val cap = detector().currentCap()

            // Only the 500 KB chunk was fetched: 500_000 × 8 ÷ 1.5 s ≈ 2.67 Mbps, ×0.8.
            cap shouldBe 2_133_332
            coVerify(exactly = 1) { api.getBitrateTestBytes(any()) }
        }

    @Test
    fun `the chunk that ended the ramp is counted, bytes and time both`() =
        runTest {
            // 500 KB in 200 ms (20 Mbps), then 1 MB in 1.5 s — the slow chunk that stops the ramp.
            answerChunks(200L, 1_500L)

            val cap = detector().currentCap()

            // 1_500_000 bytes × 8 ÷ 1.7 s ≈ 7.06 Mbps, ×0.8. Neither the fast chunk alone
            // (16 Mbps) nor the slow one alone (4.27 Mbps): the ramp answers with all of it.
            cap shouldBe 5_647_058
            coVerify(exactly = 2) { api.getBitrateTestBytes(any()) }
        }

    @Test
    fun `a hopeless link is still clamped onto the ladder's bottom rung`() =
        runTest {
            // 500 KB in five seconds — 800 kbps, and 640 kbps after the headroom.
            answerChunks(5_000L)

            // Below the lowest rung the fallback ladder would have nothing to step down to, so the
            // first source error would give up instead of retrying.
            detector().currentCap() shouldBe 720_000
        }

    @Test
    fun `a link faster than the device profile is clamped to its ceiling`() =
        runTest {
            answerChunks(1L)

            detector().currentCap() shouldBe 120_000_000
        }

    // ---- the cache ------------------------------------------------------------------------------

    @Test
    fun `a fresh measurement is reused instead of measured again`() =
        runTest {
            answerChunks(200L, 400L, 800L)
            val detector = detector()

            detector.currentCap() shouldBe 20_571_428
            clock += 14 * 60 * 1_000L
            detector.currentCap() shouldBe 20_571_428

            // Fourteen minutes later the link is still the link; asking again costs a round trip on
            // the one screen that must not be slow.
            coVerify(exactly = 3) { api.getBitrateTestBytes(any()) }
        }

    @Test
    fun `a measurement older than the ttl is taken again`() =
        runTest {
            answerChunks(200L, 400L, 800L)
            val detector = detector()

            detector.currentCap()
            clock += 16 * 60 * 1_000L
            detector.currentCap()

            coVerify(exactly = 6) { api.getBitrateTestBytes(any()) }
        }

    @Test
    fun `concurrent callers share one measurement`() =
        runTest {
            answerChunks(200L, 400L, 800L)
            val detector = detector()

            val first = async { detector.currentCap() }
            val second = async { detector.currentCap() }

            first.await() shouldBe 20_571_428
            second.await() shouldBe 20_571_428
            // The second caller waits for the one in flight rather than starting a competing
            // transfer that would measure the first one's own traffic.
            coVerify(exactly = 3) { api.getBitrateTestBytes(any()) }
        }

    // ---- persistence ----------------------------------------------------------------------------

    @Test
    fun `a completed measurement is written down for the next app start`() =
        runTest {
            answerChunks(200L, 400L, 800L)

            detector().currentCap()

            coVerify(exactly = 1) { preferences.setMaxStreamingBitrate(20_571_428) }
        }

    // ---- what a failed measurement degrades to ---------------------------------------------------

    @Test
    fun `a measurement that runs out of budget falls back on what this run already knew`() =
        runTest {
            answerChunks(200L, 400L, 800L)
            val detector = detector()
            detector.currentCap() shouldBe 20_571_428

            clock += 16 * 60 * 1_000L
            coEvery { api.getBitrateTestBytes(any()) } coAnswers {
                delay(10_000)
                ByteArray(firstArg())
            }

            // The stale number describes this link better than no number at all does.
            detector.currentCap() shouldBe 20_571_428
        }

    @Test
    fun `a timed-out measurement is not remembered as the new truth`() =
        runTest {
            answerChunks(200L, 400L, 800L)
            val detector = detector()
            detector.currentCap()

            clock += 16 * 60 * 1_000L
            coEvery { api.getBitrateTestBytes(any()) } coAnswers {
                delay(10_000)
                ByteArray(firstArg())
            }
            detector.currentCap()

            // Only the completed measurement was ever written down; a torn-down one is not evidence.
            coVerify(exactly = 1) { preferences.setMaxStreamingBitrate(any()) }
        }

    @Test
    fun `a failure with nothing measured yet falls back on the stored prior`() =
        runTest {
            every { preferences.maxStreamingBitrate } returns flowOf(7_000_000)
            coEvery { api.getBitrateTestBytes(any()) } throws IOException("no route to host")

            detector().currentCap() shouldBe 7_000_000
        }

    @Test
    fun `a failure with nothing known at all degrades to no cap`() =
        runTest {
            coEvery { api.getBitrateTestBytes(any()) } throws IOException("no route to host")

            // Which is exactly the behaviour Auto had before this class existed.
            detector().currentCap().shouldBeNull()
        }
}
