package dev.jellyboost.player.ui

import androidx.media3.common.PlaybackException
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.cast.CastConnection
import dev.jellyboost.player.cast.CastMetadata
import dev.jellyboost.player.cast.CastMetadataHolder
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * Uses real [RoutingPlayerHandle]/[CastSessionCoordinator] over fakes so "exactly one stop report
 * per source" is verified as a system property, not two independently-mocked halves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastTest : PlayerViewModelFixture() {
    private val local get() = playerHandle

    private val castHandle = FakePlayerHandle()

    private val routing = RoutingPlayerHandle(local, Provider { castHandle })

    private val castStatus = CastStatusHolder()

    /** What the receiver would be loaded with; the handle reads it at `prepare`. */
    private val castMetadata = CastMetadataHolder()

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

    /** Own builder, not the fixture's: the handle and the session controller both need the routing one. */
    private fun castViewModel(): PlayerViewModel =
        PlayerViewModel(
            repository = repository,
            sessionController =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    playerHandle = routing,
                    reporter = reporter,
                    // Must be the same holder the coordinator writes: the controller re-checks it at
                    // prepare time, and a private never-casting default would re-resolve every open.
                    castStatus = castStatus,
                ),
            playerHandle = routing,
            reporter = reporter,
            fallback = DecoderFallbackHandler(),
            trickplayResolver = trickplayResolver,
            segmentLoader = segmentLoader,
            upNextResolver = upNextResolver,
            preferences = preferences,
            pipController = pipController,
            connectionState = connectionState,
            syncPlayController = syncPlayController,
            syncPlayLocalSession = syncPlayLocalSession,
            savedStateHandle = navArgs(),
            castStatus = castStatus,
            castMetadata = castMetadata,
            castCoordinator = coordinator,
        )

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

            // Must close the outgoing session before negotiating the next: `reportStop` kills its
            // encoder, and a `PlaybackInfo` that overtook it would strand one.
            coVerifyOrder {
                reporter.reportStop(source, ON_THE_PHONE)
                resolver.resolve(any())
            }
            requests.last().castTarget shouldBe true
            requests.last().startPositionTicks shouldBe ON_THE_PHONE.positionMs.millisToTicks()
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

            // Forgetting this leaves two players sounding at once (decision 1).
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
            // A disconnect is not a request to watch; the user presses play.
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

            // Stopping/releasing here would stop a real television; the stop report is the
            // coordinator's from now on.
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
            requests.shouldBeEmpty()
        }

    @Test
    fun `a download being streamed for a track keeps streaming it on the television`() =
        runTest(dispatcher) {
            // `ActiveSession.forcedRemote` must carry across the transfer: dropping it would
            // renegotiate the receiver's stream against the download and lose the server-only track.
            local.trackSelectionSucceeds = false
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.downloadedFilm())
            val model = castViewModel()
            advanceUntilIdle()

            coEvery { resolver.resolve(any()) } returns
                AppResult.Success(source.copy(selectedAudioIndex = PlayerFixtures.STREAMED_AUDIO_INDEX))
            model.selectAudioTrack(PlayerFixtures.STREAMED_AUDIO_INDEX)
            advanceUntilIdle()
            val requests = recordResolves()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            requests.last().castTarget shouldBe true
            requests.last().forceRemote shouldBe true
        }

    // ---- control parity ---------------------------------------------------------------------------

    @Test
    fun `an audio switch on a receiver is renegotiated for the receiver`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            // The handle refusing the switch is the contract that routes it back to `PlaybackInfo`.
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

            // The ladder diagnoses this device's decoders; the receiver's are elsewhere (decision 8).
            requests.shouldBeEmpty()
            model.uiState.value.errorMessage shouldBe UiText.Raw("boom")
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

    // ---- what the screen draws (Phase 4) -----------------------------------------------------------

    @Test
    fun `picture-in-picture is disarmed while a television has the film`() =
        runTest(dispatcher) {
            val model = castViewModel()
            advanceUntilIdle()
            local.emit(PlayerEvent.IsPlayingChanged(true))
            advanceUntilIdle()
            model.setScreenPresent(true)
            pipController.state.value.canEnter shouldBe true

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            // Nothing to float: the cast handle has no surface, so PiP would be a black rectangle.
            pipController.state.value.canEnter shouldBe false
        }

    @Test
    fun `a receiver with no playback rate takes the speed picker with it`() =
        runTest(dispatcher) {
            castHandle.supportsPlaybackSpeed = false
            val model = castViewModel()
            advanceUntilIdle()
            // Local playback always has a rate, so the control is there until the receiver is.
            model.uiState.value.canSetSpeed shouldBe true

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            model.uiState.value.canSetSpeed shouldBe false

            framework.onSessionEnded()
            advanceUntilIdle()

            model.uiState.value.canSetSpeed shouldBe true
        }

    @Test
    fun `a receiver that does have a rate keeps the picker`() =
        runTest(dispatcher) {
            castHandle.supportsPlaybackSpeed = true
            val model = castViewModel()
            advanceUntilIdle()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            model.uiState.value.canSetSpeed shouldBe true
        }

    @Test
    fun `a rate the receiver only admits to once it has loaded something is picked up at ready`() =
        runTest(dispatcher) {
            // A `CastPlayer` only publishes its receiver's commands after a load.
            castHandle.supportsPlaybackSpeed = false
            val model = castViewModel()
            advanceUntilIdle()
            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()
            model.uiState.value.canSetSpeed shouldBe false

            castHandle.supportsPlaybackSpeed = true
            castHandle.emit(PlayerEvent.Ready)
            advanceUntilIdle()

            model.uiState.value.canSetSpeed shouldBe true
        }

    @Test
    fun `the artwork behind the casting label is the item's backdrop`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(any()) } returns AppResult.Success(item(backdrop = BACKDROP, primary = POSTER))

            val model = castViewModel()
            advanceUntilIdle()

            // Fetched with the title, not on connect: needed the instant the surface goes.
            model.uiState.value.artworkUrl shouldBe BACKDROP
        }

    @Test
    fun `an item with no backdrop falls back to its poster`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(any()) } returns AppResult.Success(item(backdrop = null, primary = POSTER))

            val model = castViewModel()
            advanceUntilIdle()

            model.uiState.value.artworkUrl shouldBe POSTER
        }

    // ---- what the television says it is playing (Phase 5) -----------------------------------------

    @Test
    fun `the receiver is told what it is playing, and where to get the picture`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(any()) } returns AppResult.Success(item(backdrop = BACKDROP, primary = POSTER))

            castViewModel()
            advanceUntilIdle()

            // `PlaybackInfo` responses name nothing; without this the receiver shows an unlabelled stream.
            castMetadata.metadataFor(PlayerFixtures.ITEM_ID.toString()) shouldBe
                CastMetadata(title = "Arrival", subtitle = null, posterUrl = BACKDROP)
        }

    @Test
    fun `a cast open waits for the item, because a receiver is only loaded once`() =
        runTest(dispatcher) {
            val named = CompletableDeferred<Unit>()
            coEvery { repository.getItem(any()) } coAnswers {
                named.await()
                AppResult.Success(item(backdrop = BACKDROP, primary = POSTER))
            }
            castStatus.setConnection(CastConnection.Connected("Living Room TV"))
            val requests = recordResolves()

            castViewModel()
            advanceUntilIdle()

            // Loading the receiver now would put a nameless film on it for the rest of the session.
            requests.shouldBeEmpty()

            named.complete(Unit)
            advanceUntilIdle()

            requests.single().castTarget shouldBe true
            castMetadata.metadataFor(PlayerFixtures.ITEM_ID.toString()).title shouldBe "Arrival"
        }

    @Test
    fun `local playback waits for nothing, because a title arriving late is invisible`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(any()) } coAnswers {
                CompletableDeferred<Unit>().await()
                error("unreachable")
            }
            val requests = recordResolves()

            castViewModel()
            advanceUntilIdle()

            // The first frame is never behind a cosmetic fetch on this device.
            requests.single().castTarget shouldBe false
            castHandle.prepared.shouldBeEmpty()
        }

    private fun item(
        backdrop: String?,
        primary: String?,
    ) = JellyfinItem(
        id = "x",
        name = "Arrival",
        type = ItemType.MOVIE,
        backdropImageUrl = backdrop,
        primaryImageUrl = primary,
    )

    private companion object {
        const val BACKDROP = "https://server/Items/x/Images/Backdrop"
        const val POSTER = "https://server/Items/x/Images/Primary"

        /** Ten minutes in, and playing — an unmistakable position for the handover to resume at. */
        val ON_THE_PHONE = PlaybackSnapshot(positionMs = 600_000L, isPlaying = true)

        /** Fifteen minutes in: where the television got to before it was disconnected. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)
    }
}
