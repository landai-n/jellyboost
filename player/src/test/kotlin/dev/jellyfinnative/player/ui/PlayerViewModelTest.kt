package dev.jellyfinnative.player.ui

import androidx.media3.common.PlaybackException
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.PlaybackSpeed
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.session.PlayerEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlayerViewModel].
 *
 * The player's hard parts are not "does it play" but the sequencing around it: which request goes
 * out when, what happens to the previous transcode, and whether the stop report survives the
 * screen closing. All of that lives here and none of it needs a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelTest : PlayerViewModelFixture() {
    // ---- opening ------------------------------------------------------------------------------

    @Test
    fun `resolves the route's item at its resume position and prepares the player`() =
        runTest(dispatcher) {
            val request = slot<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(request)) } returns AppResult.Success(source)

            viewModel()
            advanceUntilIdle()

            request.captured.itemId shouldBe PlayerFixtures.ITEM_ID
            request.captured.mediaSourceId shouldBe MEDIA_SOURCE_ID
            request.captured.startPositionTicks shouldBe RESUME_TICKS
            playerHandle.prepared.single().startPositionMs shouldBe RESUME_TICKS / 10_000L
            playerHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `publishes the play method and the tracks the server negotiated`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.playMethod shouldBe PlayMethod.DIRECT_PLAY
            state.audioTracks.map { it.index } shouldContainExactly listOf(1, 2)
            state.selectedAudioIndex shouldBe 1
            state.durationMs shouldBe PlayerFixtures.RUN_TIME_TICKS / 10_000L
            state.errorMessage.shouldBeNull()
        }

    @Test
    fun `reports the start once the stream is open`() =
        runTest(dispatcher) {
            viewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { reporter.reportStart(source, any()) }
            every { reporter.startReporting(any(), any(), any()) } returns Job()
        }

    @Test
    fun `surfaces a resolve failure instead of a blank screen`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.errorMessage!! shouldContain "server"
            model.uiState.value.isLoading shouldBe false
        }

    @Test
    fun `surfaces a source it cannot build a stream URL for`() =
        runTest(dispatcher) {
            every { mediaSourceFactory.create(any()) } returns null

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.errorMessage
                .shouldNotBeNull()
            playerHandle.prepared.size shouldBe 0
        }

    // ---- player events ------------------------------------------------------------------------

    @Test
    fun `clears the loading state when the player is ready`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ready)
            advanceUntilIdle()

            model.uiState.value.isBuffering shouldBe false
            model.uiState.value.isLoading shouldBe false
        }

    @Test
    fun `reports the stop when the item plays to its end`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe true
            coVerify(exactly = 1) { reporter.reportStop(source, match { it.hasEnded }) }
        }

    @Test
    fun `does not report the stop twice when the screen closes after the item ended`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.releaseSession()

            coVerify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    // ---- decoder fallback ---------------------------------------------------------------------

    @Test
    fun `a decoder failure re-resolves with direct play and direct stream forbidden`() =
        runTest(dispatcher) {
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 60_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(source.copy(playMethod = PlayMethod.TRANSCODE))

            playerHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_DECODING_FAILED, "boom"))
            advanceUntilIdle()

            val retry = requests.last()
            retry.enableDirectPlay shouldBe false
            retry.enableDirectStream shouldBe false
            retry.startPositionTicks shouldBe 600_000_000L
            model.uiState.value.userMessage shouldBe PlayerMessage.SwitchedToTranscode
            model.uiState.value.playMethod shouldBe PlayMethod.TRANSCODE
        }

    @Test
    fun `every re-resolve stops the outgoing transcode first`() =
        runTest(dispatcher) {
            val transcoding = source.copy(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = 20_000_000)
            coEvery { resolver.resolve(any()) } returns AppResult.Success(transcoding)
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // Skipping this is what strands an ffmpeg process on the server per quality change.
            coVerify(exactly = 1) { reporter.stopTranscoding(transcoding) }
        }

    @Test
    fun `the stop reaches the server before the request for the next stream does`() =
        runTest(dispatcher) {
            val transcoding = source.copy(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = 20_000_000)
            coEvery { resolver.resolve(any()) } returns AppResult.Success(transcoding)
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // Count is not enough: launched as independent coroutines these two race, and the losing
            // order leaves the old encoder running against a session nobody will stop.
            coVerifyOrder {
                reporter.stopTranscoding(transcoding)
                resolver.resolve(match { it.maxStreamingBitrate == PlaybackQuality.LOW.maxStreamingBitrate })
            }
        }

    @Test
    fun `gives up visibly once the fallback ladder is exhausted`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_DECODING_FAILED, "boom"))
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_DECODING_FAILED, "boom again"))
            advanceUntilIdle()

            model.uiState.value.errorMessage shouldBe "boom again"
            model.uiState.value.userMessage shouldBe PlayerMessage.PlaybackFailed
        }

    // ---- quality and tracks ---------------------------------------------------------------------

    @Test
    fun `choosing a quality re-resolves with that bitrate cap`() =
        runTest(dispatcher) {
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(source.copy(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = 3_000_000))

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // A cap below the file's bitrate is what forces the server to transcode.
            requests.last().maxStreamingBitrate shouldBe 3_000_000
            requests.last().startPositionTicks shouldBe 300_000_000L
            model.uiState.value.playMethod shouldBe PlayMethod.TRANSCODE
        }

    @Test
    fun `choosing the quality already in force does nothing`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.AUTO)
            advanceUntilIdle()

            coVerify(exactly = 1) { resolver.resolve(any()) }
        }

    @Test
    fun `an audio switch the stream already contains happens without a reload`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = true
            val model = viewModel()
            advanceUntilIdle()

            model.selectAudioTrack(2)
            advanceUntilIdle()

            playerHandle.selectedAudioIndices shouldContainExactly listOf(2)
            model.uiState.value.selectedAudioIndex shouldBe 2
            coVerify(exactly = 1) { resolver.resolve(any()) }
        }

    @Test
    fun `an audio switch the stream cannot satisfy is re-requested from the server`() =
        runTest(dispatcher) {
            // The transcoding case: the server only ever sent the one audio track it was asked for.
            playerHandle.trackSelectionSucceeds = false
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.selectAudioTrack(2)
            advanceUntilIdle()

            requests.last().audioStreamIndex shouldBe 2
            model.uiState.value.userMessage shouldBe PlayerMessage.RestartedForTrackChange
        }

    @Test
    fun `turning subtitles off that the server has burned in re-requests the stream with minus one`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)

            model.selectSubtitleTrack(null)
            advanceUntilIdle()

            // null would make the server re-select the item's default subtitle.
            requests.last().subtitleStreamIndex shouldBe -1
        }

    // ---- the selection the open itself resolved -------------------------------------------------

    @Test
    fun `applies the audio and subtitle the open resolved once the player has its tracks`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(selectedSubtitleIndex = 3))

            viewModel()
            advanceUntilIdle()

            // Nothing yet: `prepare` has run but `Player.currentTracks` is still empty.
            playerHandle.selectedSubtitleIndices.shouldBeEmpty()

            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            playerHandle.selectedAudioIndices shouldContainExactly listOf(1)
            playerHandle.selectedSubtitleIndices shouldContainExactly listOf(3)
        }

    @Test
    fun `applies the open's selection once, not on every tracks event`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(selectedSubtitleIndex = 3))
            viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            playerHandle.selectedAudioIndices shouldContainExactly listOf(1)
            playerHandle.selectedSubtitleIndices shouldContainExactly listOf(3)
        }

    @Test
    fun `retries the open's selection until the player has the track it names`() =
        runTest(dispatcher) {
            // Tracks arrive in stages — a side-loaded subtitle's group lands after the container's —
            // so the first event need not hold the group the selection needs.
            playerHandle.trackSelectionSucceeds = false
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(selectedSubtitleIndex = 3))
            viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            playerHandle.trackSelectionSucceeds = true
            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            playerHandle.selectedAudioIndices shouldContainExactly listOf(1, 1)
            playerHandle.selectedSubtitleIndices shouldContainExactly listOf(3, 3)
        }

    @Test
    fun `an open's selection the player refuses never re-resolves the stream`() =
        runTest(dispatcher) {
            // The burned-in case: the server rendered the subtitle into the video, so there is no
            // text group to select and it is already on screen. Re-requesting it would restart
            // playback in a loop for something the user can already see.
            playerHandle.trackSelectionSucceeds = false
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(selectedSubtitleIndex = 3))
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            coVerify(exactly = 1) { resolver.resolve(any()) }
            playerHandle.prepared.size shouldBe 1
            model.uiState.value.userMessage
                .shouldBeNull()
        }

    @Test
    fun `subtitles off at the start is applied rather than left to the player`() =
        runTest(dispatcher) {
            // `prepare` re-enables the text renderer for the new item, and ExoPlayer's selector would
            // otherwise pick up a default-flagged text track on its own.
            viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            playerHandle.selectedSubtitleIndices shouldContainExactly listOf(null)
        }

    @Test
    fun `a subtitle chosen before the tracks event is not overwritten by the open's own`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(selectedSubtitleIndex = 3))
            val model = viewModel()
            advanceUntilIdle()

            model.selectSubtitleTrack(5)
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            playerHandle.selectedSubtitleIndices shouldContainExactly listOf(5)
            model.uiState.value.selectedSubtitleIndex shouldBe 5
        }

    @Test
    fun `a subtitle the server had to re-encode for is applied again on the new stream`() =
        runTest(dispatcher) {
            playerHandle.trackSelectionSucceeds = false
            val model = viewModel()
            advanceUntilIdle()
            coEvery { resolver.resolve(any()) } returns
                AppResult.Success(source.copy(playMethod = PlayMethod.TRANSCODE, selectedSubtitleIndex = 7))

            model.selectSubtitleTrack(7)
            advanceUntilIdle()

            playerHandle.trackSelectionSucceeds = true
            playerHandle.emit(PlayerEvent.TracksChanged)
            advanceUntilIdle()

            // The refused local switch, then the re-resolved stream's own selection reaching the
            // player — which is the half that used to be missing.
            playerHandle.selectedSubtitleIndices shouldContainExactly listOf(7, 7)
        }

    // ---- teardown -----------------------------------------------------------------------------

    @Test
    fun `hands the final stop report to the scope that outlives the screen`() =
        runTest(dispatcher) {
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 45_000L)
            val model = viewModel()
            advanceUntilIdle()

            model.releaseSession()

            // viewModelScope is already cancelled here, so a report launched on it would vanish.
            coVerify(exactly = 1) { reporter.reportStopDetached(source, any()) }
            playerHandle.stopped shouldBe true
        }

    @Test
    fun `leaving the screen gives the player back rather than idling it forever`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.releaseSession()

            // `stop()` frees the codecs and the audio focus but keeps the playback thread, the
            // loaders, the allocator's buffers and the ffmpeg renderer for the life of the process
            // (audit STAB-05).
            playerHandle.releaseCount shouldBe 1
        }

    @Test
    fun `reports nothing when the screen closes before anything resolved`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())
            val model = viewModel()
            advanceUntilIdle()

            model.releaseSession()

            coVerify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        }

    // ---- M8, playing a download -------------------------------------------------------------------

    @Test
    fun `a downloaded item opens off its file with no quality control on screen`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.localSource())

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.isLocalPlayback shouldBe true
            model.uiState.value.playMethod shouldBe PlayMethod.DIRECT_PLAY
            model.uiState.value.errorMessage
                .shouldBeNull()
        }

    @Test
    fun `the quality picker is inert for a download, so it cannot reload the file`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.localSource())
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // There is no bitrate to cap, so nothing is re-resolved and nothing reloads.
            coVerify(exactly = 1) { resolver.resolve(any()) }
            model.uiState.value.quality shouldBe PlaybackQuality.AUTO
        }

    @Test
    fun `leaving a downloaded item still hands the stop report to the detached scope`() =
        runTest(dispatcher) {
            // Offline that report writes nothing but the local position — which is the only record
            // of where the user got to.
            val local = PlayerFixtures.localSource()
            coEvery { resolver.resolve(any()) } returns AppResult.Success(local)
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 45_000L)
            val model = viewModel()
            advanceUntilIdle()

            model.releaseSession()

            coVerify(exactly = 1) { reporter.reportStopDetached(local, any()) }
        }

    // ---- M9: speed ----------------------------------------------------------------------------

    @Test
    fun `applies a chosen playback rate to the player`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.selectSpeed(PlaybackSpeed.ONE_AND_HALF)

            playerHandle.playbackSpeeds shouldContainExactly listOf(1.5f)
            model.uiState.value.speed shouldBe PlaybackSpeed.ONE_AND_HALF
        }

    @Test
    fun `re-applies the session's speed after a re-resolve`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            model.selectSpeed(PlaybackSpeed.DOUBLE)

            // A quality change rebuilds the media item, which starts at 1x again.
            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            playerHandle.playbackSpeeds shouldContainExactly listOf(2f, 2f)
        }

    @Test
    fun `does not touch the player when the chosen rate is the current one`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.selectSpeed(PlaybackSpeed.NORMAL)

            playerHandle.playbackSpeeds.shouldBeEmpty()
        }

    // ---- M9: trickplay ------------------------------------------------------------------------

    @Test
    fun `publishes the scrubbing thumbnails once they resolve`() =
        runTest(dispatcher) {
            coEvery { trickplayResolver.resolve(any(), any()) } returns tiles

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.trickplay shouldBe tiles
        }

    @Test
    fun `an item without thumbnails leaves the scrubber plain`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.trickplay
                .shouldBeNull()
        }

    // ---- M9: media segments -------------------------------------------------------------------

    @Test
    fun `offers a skip while playback is inside an intro`() =
        runTest(dispatcher) {
            coEvery { segmentLoader.load(any()) } returns listOf(intro)
            val model = viewModel()
            advanceUntilIdle()

            model.onTick(PlaybackSnapshot(positionMs = 60_000L))

            model.uiState.value.skippableSegment shouldBe intro
        }

    @Test
    fun `the skip button seeks to the end of the segment and then withdraws the offer`() =
        runTest(dispatcher) {
            coEvery { segmentLoader.load(any()) } returns listOf(intro)
            val model = viewModel()
            advanceUntilIdle()
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))

            model.skipCurrentSegment()

            playerHandle.snapshot.positionMs shouldBe intro.endMs
            model.uiState.value.skippableSegment
                .shouldBeNull()
        }

    @Test
    fun `auto-skip seeks past the intro without waiting to be asked`() =
        runTest(dispatcher) {
            every { preferences.introSkipMode } returns flowOf(SegmentSkipMode.AUTO_SKIP)
            coEvery { segmentLoader.load(any()) } returns listOf(intro)

            val model = viewModel()
            advanceUntilIdle()
            model.onTick(PlaybackSnapshot(positionMs = 35_000L))

            playerHandle.snapshot.positionMs shouldBe intro.endMs
            model.uiState.value.skippableSegment
                .shouldBeNull()
        }

    @Test
    fun `a downloaded item is never offered a skip`() =
        runTest(dispatcher) {
            // The loader is server-only and answers empty for a local source; the ViewModel must
            // then draw nothing rather than reusing the previous item's segments.
            coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.localSource())
            coEvery { segmentLoader.load(any()) } returns emptyList()

            val model = viewModel()
            advanceUntilIdle()
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))

            model.uiState.value.skippableSegment
                .shouldBeNull()
        }

    // ---- the ticking half of the state (PERF-04) ------------------------------------------------

    @Test
    fun `a tick moves the seek bar`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.onTick(PlaybackSnapshot(positionMs = 60_000L, bufferedMs = 90_000L, isPlaying = true))

            model.position.value shouldBe PlaybackPosition(positionMs = 60_000L, bufferedMs = 90_000L)
        }

    @Test
    fun `a tick that only moves the position leaves the rest of the state alone`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            model.onTick(PlaybackSnapshot(positionMs = 60_000L, isPlaying = true))
            val before = model.uiState.value

            model.onTick(PlaybackSnapshot(positionMs = 60_500L, isPlaying = true))

            // The whole point of splitting the flow: at 2 Hz, an unchanged `PlayerUiState` is what
            // lets the top bar, the transport row and the pickers skip recomposition entirely.
            model.uiState.value shouldBeSameInstanceAs before
            model.position.value.positionMs shouldBe 60_500L
        }

    @Test
    fun `a seek publishes the new position without waiting for the poll`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.seekTo(90_000L)

            model.position.value.positionMs shouldBe 90_000L
        }

    // ---- M9: picture-in-picture ---------------------------------------------------------------

    @Test
    fun `arms picture-in-picture only while the screen is up and something is playing`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            pipController.state.value.canEnter shouldBe false

            playerHandle.emit(PlayerEvent.IsPlayingChanged(true))
            playerHandle.emit(PlayerEvent.VideoSizeChanged(1920, 1080))
            runCurrent()
            model.setScreenPresent(true)

            pipController.state.value.canEnter shouldBe true
            pipController.state.value.aspectRatio shouldBe (1920 to 1080)
        }

    @Test
    fun `disarms picture-in-picture when the preference is off`() =
        runTest(dispatcher) {
            every { preferences.pipOnLeave } returns flowOf(false)

            val model = viewModel()
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.IsPlayingChanged(true))
            runCurrent()
            model.setScreenPresent(true)

            pipController.state.value.canEnter shouldBe false
        }

    @Test
    fun `disarms picture-in-picture when the player screen goes away`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.IsPlayingChanged(true))
            runCurrent()
            model.setScreenPresent(true)
            pipController.state.value.canEnter shouldBe true

            model.releaseSession()

            pipController.state.value.canEnter shouldBe false
        }

    // ---- process death ------------------------------------------------------------------------

    @Test
    fun `a restore comes back where playback got to, not where the user tapped Play`() =
        runTest(dispatcher) {
            val request = slot<PlaybackResolveRequest>()
            // The real resolver echoes the requested position back on the source it returns, and
            // that is the number the player is prepared at.
            coEvery { resolver.resolve(capture(request)) } coAnswers {
                AppResult.Success(source.copy(startPositionTicks = request.captured.startPositionTicks))
            }

            viewModel(
                navArgs(
                    PlayerViewModel.KEY_LIVE_POSITION_TICKS to LIVE_TICKS,
                    PlayerViewModel.KEY_WAS_PLAYING to true,
                ),
            )
            advanceUntilIdle()

            // The navigation argument is an hour behind: replaying from it would let the next
            // progress tick stamp a stale position with a fresh timestamp, and most-recent-wins
            // sync would then push it to the server and every other device.
            request.captured.startPositionTicks shouldBe LIVE_TICKS
            playerHandle.prepared.single().startPositionMs shouldBe LIVE_TICKS / 10_000L
            playerHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `a session that was paused when the process died comes back paused`() =
        runTest(dispatcher) {
            viewModel(
                navArgs(
                    PlayerViewModel.KEY_LIVE_POSITION_TICKS to LIVE_TICKS,
                    PlayerViewModel.KEY_WAS_PLAYING to false,
                ),
            )
            advanceUntilIdle()

            // Coming back to an app abandoned hours ago must not start talking to an empty room.
            playerHandle.prepared.single().playWhenReady shouldBe false
        }

    @Test
    fun `the position a progress tick writes is the position the next process starts from`() =
        runTest(dispatcher) {
            val handle = navArgs()
            val snapshot = slot<() -> PlaybackSnapshot>()
            every { reporter.startReporting(any(), any(), capture(snapshot)) } returns Job()

            viewModel(handle)
            advanceUntilIdle()
            playerHandle.snapshot = PlaybackSnapshot(positionMs = LIVE_TICKS / 10_000L, isPlaying = true)
            // One 5-second reporter tick — the ticker that keeps running behind a backgrounded
            // screen, which is exactly the state a process death happens in.
            snapshot.captured()

            val request = slot<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(request)) } returns AppResult.Success(source)
            playerHandle.prepared.clear()
            viewModel(handle)
            advanceUntilIdle()

            request.captured.startPositionTicks shouldBe LIVE_TICKS
            playerHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `a fresh navigation writes nothing to the handle until playback has a position`() =
        runTest(dispatcher) {
            val handle = navArgs()
            val snapshot = slot<() -> PlaybackSnapshot>()
            every { reporter.startReporting(any(), any(), capture(snapshot)) } returns Job()

            viewModel(handle)
            advanceUntilIdle()
            snapshot.captured()

            // Position 0 is indistinguishable from "no session yet"; the route's own resume
            // argument is the better answer for it, so nothing is recorded over it.
            handle.get<Long>(PlayerViewModel.KEY_LIVE_POSITION_TICKS).shouldBeNull()
            handle.get<Long>(PlayerViewModel.ARG_START_TICKS) shouldBe RESUME_TICKS
        }
}
