package dev.jellyboost.player.session

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.cast.CastConnection
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.resolve.ExoMediaSourceFactory
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.resolve.PlaybackSourceResolver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

/**
 * The ordering tests are the point: `reopen` must run its work so that `stopTranscoding`
 * provably happens *first*, not merely happens — "first" is the entire reason the call exists.
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
            ioDispatcher = UnconfinedTestDispatcher(),
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

            // Order, not count: asking the server for the next stream first leaves the old ffmpeg
            // process running against a session nobody will ever stop.
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

    // ---- the relinquish closure ------------------------------------------------------------------

    @Test
    fun `the relinquish marshals its player calls onto the main dispatcher, report in between`() =
        runTest {
            // The arbiter runs video's relinquish inline in the *claimant's* context — for a music
            // claim that is a background dispatcher — so the closure itself must hop the
            // player-touching steps (snapshot, stop) onto main, with the stop report between them.
            val transcript = mutableListOf<String>()
            val recordingMain =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        transcript += "main hop"
                        block.run()
                    }
                }
            coEvery { reporter.reportStop(any(), any()) } coAnswers { transcript += "stop report" }
            val handover = PlaybackHandover()
            val controller =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    playerHandle = playerHandle,
                    reporter = reporter,
                    handover = handover,
                    mainDispatcher = recordingMain,
                )
            controller.open(request(), playWhenReady = true)

            handover.claim(PlaybackKind.MUSIC) {}

            // The report must complete before the player is let go — the arbiter's ordering invariant.
            transcript shouldContainExactly listOf("main hop", "stop report", "main hop")
            playerHandle.stopped shouldBe true
        }

    @Test
    fun `the spec is built off the calling thread, because that is where the fonts are read`() =
        runTest {
            // Not a style point: `create` opens every attached font, and `open` runs on the main
            // thread — `prepare` has to, since Media3 binds the player to this looper. Without the hop
            // an item with a dozen attached faces opens a dozen files on the UI thread at playback
            // start. The order matters too: the hop is over before `prepare`, so nothing about libass
            // receiving its faces before the first track changes.
            val transcript = mutableListOf<String>()
            val recordingIo =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        transcript += "io hop"
                        block.run()
                    }
                }
            every { mediaSourceFactory.create(any()) } answers {
                transcript += "create"
                spec
            }
            val controller =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    ioDispatcher = recordingIo,
                    playerHandle = playerHandle,
                    reporter = reporter,
                )

            controller.open(request(), playWhenReady = true)

            transcript shouldContainExactly listOf("io hop", "create")
            playerHandle.prepared.size shouldBe 1
        }

    // ---- the cast state changing underneath a resolve ---------------------------------------------

    @Test
    fun `a cast session starting mid-resolve makes the item re-negotiate for the receiver`() =
        runTest {
            val status = CastStatusHolder()
            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } coAnswers {
                // The session starts while the first resolve is on the wire: whatever it negotiated
                // was profiled for this device's decoders, and preparing it would land on the cast
                // player the routing handle now points at.
                status.setConnection(CastConnection.Connected("Living Room TV"))
                AppResult.Success(source)
            }
            val controller =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    playerHandle = playerHandle,
                    reporter = reporter,
                    castStatus = status,
                )

            val result = controller.open(request(), playWhenReady = true)

            result.shouldBeInstanceOf<SessionOpenResult.Opened>()
            requests.map { it.castTarget } shouldBe listOf(false, true)
            // One prepare, off the re-negotiated resolve — not one per attempt.
            playerHandle.prepared.size shouldBe 1
        }

    @Test
    fun `a cast session ending mid-resolve brings the negotiation back to this device`() =
        runTest {
            val status = CastStatusHolder()
            status.setConnection(CastConnection.Connected("Living Room TV"))
            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } coAnswers {
                status.setConnection(CastConnection.None)
                AppResult.Success(source)
            }
            val controller =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    playerHandle = playerHandle,
                    reporter = reporter,
                    castStatus = status,
                )

            controller.open(request().copy(castTarget = true), playWhenReady = true)

            requests.map { it.castTarget } shouldBe listOf(true, false)
        }

    @Test
    fun `a steady cast state resolves exactly once`() =
        runTest {
            val status = CastStatusHolder()
            status.setConnection(CastConnection.Connected("Living Room TV"))
            val controller =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    playerHandle = playerHandle,
                    reporter = reporter,
                    castStatus = status,
                )

            controller.open(request().copy(castTarget = true), playWhenReady = true)

            coVerify(exactly = 1) { resolver.resolve(any()) }
        }

    private fun request() = PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID, startPositionTicks = RESUME_TICKS)

    private companion object {
        const val RESUME_TICKS = 12_000_000_000L
    }
}
