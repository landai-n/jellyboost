package dev.jellyfinnative.player.session

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.ExoMediaSourceFactory
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.resolve.PlaybackSourceResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlaybackSessionController] — the resolve → prepare sequence extracted from
 * `PlayerViewModel` (audit ARCH-10).
 *
 * The ordering test is the point of the class. `reopen` used to be two coroutines launched
 * independently, so the assertion that could be made was "stopTranscoding happened", not "it
 * happened first" — and "first" is the entire reason the call exists.
 */
class PlaybackSessionControllerTest {
    private val resolver = mockk<PlaybackSourceResolver>()
    private val mediaSourceFactory = mockk<ExoMediaSourceFactory>()
    private val playerHandle = FakePlayerHandle()
    private val reporter = mockk<PlaybackReporter>(relaxed = true)

    private val controller =
        PlaybackSessionController(
            resolver = resolver,
            mediaSourceFactory = mediaSourceFactory,
            playerHandle = playerHandle,
            reporter = reporter,
        )

    private val source = PlayerFixtures.remoteSource(startPositionTicks = RESUME_TICKS)
    private val spec = PlaybackMediaItemSpec(mediaId = PlayerFixtures.ITEM_ID.toString(), uri = "https://server/x")

    @BeforeEach
    fun setUp() {
        coEvery { resolver.resolve(any()) } returns AppResult.Success(source)
        every { mediaSourceFactory.create(any()) } returns spec
    }

    @Test
    fun `prepares the player at the position the resolver echoed back`() =
        runTest {
            val result = controller.open(request(), playWhenReady = true)

            result.shouldBeInstanceOf<SessionOpenResult.Opened>().source shouldBe source
            playerHandle.prepared.single().spec shouldBe spec
            playerHandle.prepared.single().startPositionMs shouldBe RESUME_TICKS / 10_000L
            playerHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `a paused re-resolve stays paused`() =
        runTest {
            controller.open(request(), playWhenReady = false)

            playerHandle.prepared.single().playWhenReady shouldBe false
        }

    @Test
    fun `hands back the resolver's failure rather than a message`() =
        runTest {
            val error = AppError.Network()
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(error)

            // The copy that failure turns into is the screen's business, not this class's.
            controller.open(request(), playWhenReady = true) shouldBe SessionOpenResult.ResolveFailed(error)
            playerHandle.prepared.size shouldBe 0
        }

    @Test
    fun `a source no media item can be built for never reaches the player`() =
        runTest {
            every { mediaSourceFactory.create(any()) } returns null

            controller.open(request(), playWhenReady = true) shouldBe SessionOpenResult.UnsupportedSource
            playerHandle.prepared.size shouldBe 0
        }

    @Test
    fun `the outgoing transcode is stopped before the next stream is asked for`() =
        runTest {
            val previous = source.copy(playMethod = PlayMethod.TRANSCODE)

            controller.reopen(previous, request(), playWhenReady = true)

            // Order, not count: asking the server for the next stream first is what leaves the old
            // ffmpeg process running against a session nobody will ever stop.
            coVerifyOrder {
                reporter.stopTranscoding(previous)
                resolver.resolve(any())
            }
        }

    @Test
    fun `a failed re-resolve has still killed the outgoing encoder`() =
        runTest {
            val previous = source.copy(playMethod = PlayMethod.TRANSCODE)
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())

            controller.reopen(previous, request(), playWhenReady = true)

            // The stop comes first precisely so that losing the server mid-change does not also
            // leak the encoder that was already running.
            coVerify(exactly = 1) { reporter.stopTranscoding(previous) }
        }

    @Test
    fun `reporting the start is left to the caller`() =
        runTest {
            controller.open(request(), playWhenReady = true)

            // The caller has to publish the new source first, or a player event arriving during the
            // first buffer is attributed to the source that was just replaced.
            coVerify(exactly = 0) { reporter.reportStart(any(), any()) }
        }

    private fun request() = PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, startPositionTicks = RESUME_TICKS)

    private companion object {
        const val RESUME_TICKS = 12_000_000_000L
    }
}
