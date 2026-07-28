package dev.jellyfinnative.player.report

import dev.jellyfinnative.data.userdata.UserDataRepository
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.api.PlayerApi
import dev.jellyfinnative.player.di.DetachedPlayerScope
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the server's idea of this session in step with the player's.
 *
 * Three things depend on it, and all three are visible to the user:
 * "now playing" in the server dashboard, the resume position on every other client, and — for a
 * transcode — whether an ffmpeg process is still running after we leave.
 *
 * Modelled on jellyfin-android's `PlayerViewModel.kt:410-562`, with one addition the plan
 * requires: every position that goes to the server is **also** written locally through
 * [UserDataRepository], so resume behaves identically whether the item was streamed or (from M8)
 * played from a download.
 */
@Singleton
class PlaybackReporter
    @Inject
    constructor(
        private val api: PlayerApi,
        private val userDataRepository: UserDataRepository,
        @DetachedPlayerScope private val detachedScope: CoroutineScope,
    ) {
        /** Playback started (or restarted after a re-resolve). */
        suspend fun reportStart(
            source: RemotePlaybackMediaSource,
            snapshot: PlaybackSnapshot,
        ) {
            runReport("start") {
                api.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = source.itemId,
                        playMethod = source.playMethod.toSdk(),
                        playSessionId = source.playSessionId,
                        liveStreamId = source.liveStreamId,
                        mediaSourceId = source.mediaSourceId,
                        audioStreamIndex = source.selectedAudioIndex,
                        subtitleStreamIndex = source.selectedSubtitleIndex,
                        isPaused = !snapshot.isPlaying,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = source.startPositionTicks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        }

        /**
         * One progress tick.
         *
         * The local write happens even when the server call fails — an unreachable server must not
         * cost the user their place in the film.
         */
        suspend fun reportProgress(
            source: RemotePlaybackMediaSource,
            snapshot: PlaybackSnapshot,
        ) {
            if (snapshot.hasEnded) return

            runReport("progress") {
                api.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = source.itemId,
                        playMethod = source.playMethod.toSdk(),
                        playSessionId = source.playSessionId,
                        liveStreamId = source.liveStreamId,
                        mediaSourceId = source.mediaSourceId,
                        audioStreamIndex = source.selectedAudioIndex,
                        subtitleStreamIndex = source.selectedSubtitleIndex,
                        isPaused = !snapshot.isPlaying,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = snapshot.positionTicks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }

            userDataRepository.setPosition(source.itemId.toString(), snapshot.positionTicks)
        }

        /**
         * Playback stopped.
         *
         * Finishing the item marks it watched through [UserDataRepository] rather than through a
         * bare `markPlayedItem` call, so the local row, the event bus and the server all agree
         * without a second round trip. A transcode additionally has its encoding process killed —
         * skipping that is what leaves stray ffmpeg processes on the server.
         */
        suspend fun reportStop(
            source: RemotePlaybackMediaSource,
            snapshot: PlaybackSnapshot,
        ) {
            val positionTicks = if (snapshot.hasEnded) source.runTimeTicks else snapshot.positionTicks

            runReport("stop") {
                api.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = source.itemId,
                        positionTicks = positionTicks,
                        playSessionId = source.playSessionId,
                        liveStreamId = source.liveStreamId,
                        mediaSourceId = source.mediaSourceId,
                        failed = false,
                    ),
                )
            }

            stopTranscoding(source)

            if (snapshot.hasEnded) {
                userDataRepository.setPlayed(source.itemId.toString(), played = true)
            } else {
                userDataRepository.setPosition(source.itemId.toString(), positionTicks)
            }
        }

        /**
         * Reports the stop from a scope that outlives the screen.
         *
         * `viewModelScope` is already cancelled by the time `onCleared` runs, so a stop report
         * launched there would be dropped — and with it the resume position and the ffmpeg kill.
         * The detached [SupervisorJob][DetachedPlayerScope] scope is the whole point.
         */
        fun reportStopDetached(
            source: RemotePlaybackMediaSource,
            snapshot: PlaybackSnapshot,
        ) {
            detachedScope.launch { reportStop(source, snapshot) }
        }

        /** Kills the server-side encoder if, and only if, this source is being transcoded. */
        suspend fun stopTranscoding(source: RemotePlaybackMediaSource) {
            if (source.playMethod != PlayMethod.TRANSCODE) return
            val deviceId = api.deviceId
            if (deviceId == null) {
                Timber.w("No device id; cannot stop the encoding process for %s", source.playSessionId)
                return
            }
            runReport("stopEncoding") {
                api.stopEncodingProcess(deviceId = deviceId, playSessionId = source.playSessionId)
            }
        }

        /**
         * Starts the 5-second progress ticker on [scope].
         *
         * [currentSource] and [snapshot] are read fresh on every tick rather than captured, so the
         * loop keeps reporting correctly across a re-resolve (a quality change or a fallback
         * retry) without being torn down and restarted.
         */
        fun startReporting(
            scope: CoroutineScope,
            currentSource: () -> RemotePlaybackMediaSource?,
            snapshot: () -> PlaybackSnapshot,
        ): Job =
            scope.launch {
                while (true) {
                    delay(PROGRESS_INTERVAL)
                    val source = currentSource() ?: continue
                    reportProgress(source, snapshot())
                }
            }

        /** Reporting is best effort: a failed report must never surface as a playback failure. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runReport(
            what: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Playback %s report failed", what)
            }
        }

        private companion object {
            /** Matches what jellyfin-web and jellyfin-android send; the server expects roughly this. */
            val PROGRESS_INTERVAL = 5.seconds
        }
    }

/** Our [PlayMethod] is a copy of the SDK's; this is the one place the two meet. */
private fun PlayMethod.toSdk(): org.jellyfin.sdk.model.api.PlayMethod =
    when (this) {
        PlayMethod.DIRECT_PLAY -> org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY
        PlayMethod.DIRECT_STREAM -> org.jellyfin.sdk.model.api.PlayMethod.DIRECT_STREAM
        PlayMethod.TRANSCODE -> org.jellyfin.sdk.model.api.PlayMethod.TRANSCODE
    }
