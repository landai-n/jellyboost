package dev.jellyboost.player.ui

import androidx.media3.common.PlaybackException
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.cast.CastSessionCoordinator
import dev.jellyboost.player.cast.CastSessionListener
import dev.jellyboost.player.cast.CastSessionMonitor
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.fallback.DecoderFallbackHandler
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlaybackSessionController
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.RoutingPlayerHandle
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * What the player does when a television takes the film, and when it gives it back (M12 Phase 3).
 *
 * Assembled from the real pieces wherever they are ours: a genuine [RoutingPlayerHandle] over two
 * [FakePlayerHandle]s and a genuine [CastSessionCoordinator] behind a fake [CastSessionMonitor], so
 * that "the screen reports the stop, the coordinator does not" is tested as the *system* property it
 * is rather than as two halves that were never in the same room. No `com.google.android.gms` type
 * appears anywhere: the monitor is the seam that keeps the Cast framework out.
 *
 * The claim the whole class is built around is a counting one — **exactly one stop report per
 * source**. A transfer that reported twice would kill the incoming encoder along with the outgoing
 * one and race itself for the resume position; a transfer that reported neither would leave the
 * server showing a session that is not there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastTest : PlayerViewModelFixture() {
    /** The fixture's handle is this device's; the receiver gets its own. */
    private val local get() = playerHandle

    private val castHandle = FakePlayerHandle()

    private val routing = RoutingPlayerHandle(local, Provider { castHandle })

    private val castStatus = CastStatusHolder()

    /** Captures the coordinator's listener, so a test can be the Cast framework. */
    private val monitor =
        object : CastSessionMonitor {
            var listener: CastSessionListener? = null

            override fun start(listener: CastSessionListener) {
                this.listener = listener
            }
        }

    private val coordinator by lazy {
        CastSessionCoordinator(
            monitor = monitor,
            routing = routing,
            reporter = reporter,
            status = castStatus,
            detachedScope = CoroutineScope(dispatcher),
            mainDispatcher = dispatcher,
        ).also { it.start() }
    }

    private val framework get() = requireNotNull(monitor.listener) { "The coordinator never started watching" }

    /**
     * A ViewModel wired for casting.
     *
     * Its own builder rather than the fixture's, because two of its collaborators have to be the
     * routing ones — the handle it holds *and* the one the session controller prepares through —
     * and the fixture's builder is what every pre-M12 test uses unchanged.
     */
    private fun castViewModel(): PlayerViewModel =
        PlayerViewModel(
            repository = repository,
            sessionController =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    playerHandle = routing,
                    reporter = reporter,
                ),
            playerHandle = routing,
            reporter = reporter,
            fallback = DecoderFallbackHandler(),
            trickplayResolver = trickplayResolver,
            segmentLoader = segmentLoader,
            preferences = preferences,
            pipController = pipController,
            connectionState = connectionState,
            syncPlayController = syncPlayController,
            syncPlayLocalSession = syncPlayLocalSession,
            savedStateHandle = navArgs(),
            castStatus = castStatus,
            castCoordinator = coordinator,
        )

    /** Records every request that reaches the resolver from here on. */
    private fun recordResolves(): List<PlaybackResolveRequest> {
        val requests = mutableListOf<PlaybackResolveRequest>()
        coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)
        return requests
    }

    // ---- local → cast -----------------------------------------------------------------------------

    @Test
    fun `a receiver that connects mid-playback takes the film with it, from where it had got to`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()
            local.snapshot = ON_THE_PHONE
            val requests = recordResolves()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            // The outgoing session is closed *before* the next one is negotiated: `reportStop` kills
            // its encoder on the way past, and a `PlaybackInfo` that overtook it would strand one.
            coVerifyOrder {
                reporter.reportStop(source, ON_THE_PHONE)
                resolver.resolve(any())
            }
            requests.last().castTarget shouldBe true
            requests.last().startPositionTicks shouldBe ON_THE_PHONE.positionMs.millisToTicks()
            // And it is the receiver that was prepared, not this device.
            castHandle.prepared.size shouldBe 1
            local.prepared.size shouldBe 1
        }

    @Test
    fun `a film that was playing here carries on playing there`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()
            local.snapshot = ON_THE_PHONE

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            castHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `this device stops playing when the television starts`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()
            local.resetCalls()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            // Two players sounding at once is the everyday consequence of forgetting this, and it
            // takes the local media notification down with it (decision 1).
            local.stopped shouldBe true
        }

    @Test
    fun `the screen learns which receiver has the film`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            model.uiState.value.cast shouldBe PlayerCastState(isCasting = true, deviceName = "Living Room TV")

            framework.onSessionEnded()
            advanceUntilIdle()

            model.uiState.value.cast shouldBe PlayerCastState()
        }

    @Test
    fun `the transfer is announced once, and only after it has happened`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe PlayerMessage.CastTransferred
        }

    @Test
    fun `nothing is transferred when there is nothing open`() =
        runTest(dispatcher) {
            // A session that never resolved has nothing to move, and nothing to report about it.
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())
            castViewModel()
            advanceUntilIdle()
            val requests = recordResolves()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            requests.shouldBeEmpty()
            coVerify(exactly = 0) { reporter.reportStop(any(), any()) }
        }

    // ---- cast → local -----------------------------------------------------------------------------

    @Test
    fun `a disconnect brings the film home, paused, where the television left it`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.snapshot = ON_THE_TELEVISION
            local.resetCalls()
            val requests = recordResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            requests.last().castTarget shouldBe false
            requests.last().startPositionTicks shouldBe ON_THE_TELEVISION.positionMs.millisToTicks()
            // Paused: a disconnect is not a request to watch. The user presses play.
            local.prepared.single().playWhenReady shouldBe false
        }

    @Test
    fun `the screen sends the stop report for the cast session, and the coordinator does not`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.snapshot = ON_THE_TELEVISION

            framework.onSessionEnded()
            advanceUntilIdle()

            coVerify(exactly = 1) { reporter.reportStop(source, ON_THE_TELEVISION) }
            verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    // ---- the other side of the invariant: no screen ------------------------------------------------

    @Test
    fun `a screen that goes while casting leaves the receiver playing and reports nothing`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.snapshot = ON_THE_TELEVISION
            castHandle.resetCalls()

            model.releaseSession()
            advanceUntilIdle()

            // Stopping or releasing here would be stopping a television, and the stop report is the
            // coordinator's from now on — it is the only one that will still be there when the
            // session ends.
            castHandle.stopped shouldBe false
            castHandle.releaseCount shouldBe 0
            verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    @Test
    fun `once the screen has gone the coordinator ends the session, exactly once`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.snapshot = ON_THE_TELEVISION
            model.releaseSession()
            advanceUntilIdle()
            val requests = recordResolves()

            framework.onSessionEnded()
            advanceUntilIdle()

            verify(exactly = 1) { reporter.reportStopDetached(source, ON_THE_TELEVISION) }
            // And the screen that is no longer attached transfers nothing back to a device nobody
            // is looking at.
            requests.shouldBeEmpty()
        }

    // ---- control parity ---------------------------------------------------------------------------

    @Test
    fun `an audio switch on a receiver is renegotiated for the receiver`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            // The receiver has whatever single audio track the server encoded for it, so the handle
            // refuses the switch — which is the contract that sends this back to `PlaybackInfo`.
            castHandle.trackSelectionSucceeds = false
            val requests = recordResolves()

            model.selectAudioTrack(2)
            advanceUntilIdle()

            requests.last().audioStreamIndex shouldBe 2
            requests.last().castTarget shouldBe true
        }

    @Test
    fun `a subtitle the receiver cannot render is burned in by the server, as it is locally`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            castHandle.trackSelectionSucceeds = false
            val requests = recordResolves()

            model.selectSubtitleTrack(3)
            advanceUntilIdle()

            requests.last().subtitleStreamIndex shouldBe 3
            requests.last().castTarget shouldBe true
        }

    @Test
    fun `a subtitle the receiver can render never reaches the server`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            // A side-loaded WebVTT track: `CastPlayerHandle` answers `true` and nothing restarts.
            castHandle.trackSelectionSucceeds = true
            val requests = recordResolves()

            model.selectSubtitleTrack(3)
            advanceUntilIdle()

            requests.shouldBeEmpty()
            model.uiState.value.selectedSubtitleIndex shouldBe 3
        }

    @Test
    fun `a quality change while casting is negotiated against the cast profile`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            val requests = recordResolves()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            requests.last().maxStreamingBitrate shouldBe PlaybackQuality.LOW.maxStreamingBitrate
            requests.last().castTarget shouldBe true
        }

    @Test
    fun `a receiver error is not run through the decoder fallback ladder`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            val requests = recordResolves()

            castHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_DECODING_FAILED, "boom"))
            advanceUntilIdle()

            // Every rung of the ladder diagnoses *this device's* decoders, and the decoder is three
            // metres away in a television (decision 8).
            requests.shouldBeEmpty()
            model.uiState.value.errorMessage shouldBe "boom"
            model.uiState.value.userMessage shouldBe PlayerMessage.CastPlaybackFailed
        }

    // ---- SyncPlay exclusivity ----------------------------------------------------------------------

    @Test
    fun `a receiver that connects during a group leaves the group, and says so`() =
        runTest(dispatcher) {
            syncPlayState.value = SyncPlayState.InGroup(group(), null, SyncPlayGroupState.Paused, SyncPlayPhase.Paused)
            val model = castViewModel()
            advanceUntilIdle()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            verify(exactly = 1) { syncPlayController.leaveGroup() }
            // The transfer is visible on screen a second later; being thrown out of a group is not.
            model.uiState.value.userMessage shouldBe PlayerMessage.CastLeftSyncPlayGroup
        }

    @Test
    fun `a solo session leaves no group behind it`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            verify(exactly = 0) { syncPlayController.leaveGroup() }
        }

    private companion object {
        /** Ten minutes in, and playing — an unmistakable position for the handover to resume at. */
        val ON_THE_PHONE = PlaybackSnapshot(positionMs = 600_000L, isPlaying = true)

        /** Fifteen minutes in: where the television got to before it was disconnected. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)
    }
}
