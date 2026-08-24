package dev.jellyboost.player.report

import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.data.userdata.UserDataRepository
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.di.DetachedPlayerScope
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.syncplay.SyncPlayStatusHolder
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the server's idea of this session in step with the player's: the dashboard, the resume
 * position on every other client, and whether a transcode's ffmpeg process is still running.
 *
 * **The local write is never conditional** — every position also goes through [UserDataRepository],
 * which stores the row `toBeSynced = true` regardless of the network, so an airplane-mode session
 * accumulates exactly the pending rows `UserDataSyncWorker` drains on reconnect.
 *
 * The *server* half is skipped when there is nothing to tell ([serverTarget]): a
 * [LocalPlaybackMediaSource] played alone has no `playSessionId` and no encoder, and offline every
 * call would burn a connect timeout per five-second tick. The exception is a local file **in a
 * SyncPlay group**, which reports so the member is visible to the others — but never through
 * [stopTranscoding], since a file on disk started no encoder.
 */
@Singleton
internal class PlaybackReporter
    @Inject
    constructor(
        private val api: PlayerApi,
        private val userDataRepository: UserDataRepository,
        private val connectionState: ConnectionStateProvider,
        @DetachedPlayerScope private val detachedScope: CoroutineScope,
        /** Defaulted so a reporter built without SyncPlay gets exactly the solo behaviour. */
        private val syncPlay: SyncPlayStatusHolder = SyncPlayStatusHolder(),
    ) {
        /** [repeatMode] and [playbackOrder] default to what a film always is; music passes its own. */
        suspend fun reportStart(
            source: PlaybackMediaSource,
            snapshot: PlaybackSnapshot,
            repeatMode: RepeatMode = RepeatMode.REPEAT_NONE,
            playbackOrder: PlaybackOrder = PlaybackOrder.DEFAULT,
        ) {
            val target = source.serverTarget() ?: return

            runReport("start") {
                api.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = target.itemId,
                        playMethod = target.playMethod.toSdk(),
                        playSessionId = target.playSessionId,
                        liveStreamId = target.liveStreamId,
                        mediaSourceId = target.mediaSourceId,
                        audioStreamIndex = target.selectedAudioIndex,
                        subtitleStreamIndex = target.selectedSubtitleIndex,
                        isPaused = !snapshot.isPlaying,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = target.startPositionTicks,
                        repeatMode = repeatMode,
                        playbackOrder = playbackOrder,
                    ),
                )
            }
        }

        /**
         * The local write happens even when the server call fails or is skipped. A tick whose
         * snapshot is not [valid][PlaybackSnapshot.isValid] is skipped entirely: the player no
         * longer holds this source, so its position belongs to some other session.
         */
        suspend fun reportProgress(
            source: PlaybackMediaSource,
            snapshot: PlaybackSnapshot,
            repeatMode: RepeatMode = RepeatMode.REPEAT_NONE,
            playbackOrder: PlaybackOrder = PlaybackOrder.DEFAULT,
        ) {
            if (snapshot.hasEnded) return
            if (!snapshot.isValid) {
                Timber.d("Skipping a progress tick for %s: the player no longer holds it", source.itemId)
                return
            }

            source.serverTarget()?.let { target ->
                runReport("progress") {
                    api.reportPlaybackProgress(
                        PlaybackProgressInfo(
                            itemId = target.itemId,
                            playMethod = target.playMethod.toSdk(),
                            playSessionId = target.playSessionId,
                            liveStreamId = target.liveStreamId,
                            mediaSourceId = target.mediaSourceId,
                            audioStreamIndex = target.selectedAudioIndex,
                            subtitleStreamIndex = target.selectedSubtitleIndex,
                            isPaused = !snapshot.isPlaying,
                            isMuted = false,
                            canSeek = true,
                            positionTicks = snapshot.positionTicks,
                            repeatMode = repeatMode,
                            playbackOrder = playbackOrder,
                        ),
                    )
                }
            }

            userDataRepository.setPosition(source.itemId.toString(), snapshot.positionTicks)
        }

        /**
         * A transcode additionally has its encoding process killed; skipping that leaves stray
         * ffmpeg processes on the server.
         *
         * A stop whose snapshot is not [valid][PlaybackSnapshot.isValid] still closes the session
         * and kills the encoder — both are about the *session* — but carries no position and writes
         * nothing locally, since the reading is not this source's.
         */
        suspend fun reportStop(
            source: PlaybackMediaSource,
            snapshot: PlaybackSnapshot,
        ) {
            if (!snapshot.isValid) {
                source.serverTarget()?.let { sendStopReport(it, positionTicks = null) }
                stopTranscoding(source)
                return
            }

            val positionTicks = if (snapshot.hasEnded) source.runTimeTicks else snapshot.positionTicks

            source.serverTarget()?.let { sendStopReport(it, positionTicks) }

            stopTranscoding(source)

            if (snapshot.hasEnded) {
                userDataRepository.setPlayed(source.itemId.toString(), played = true)
            } else {
                userDataRepository.setPosition(source.itemId.toString(), positionTicks)
            }
        }

        /**
         * `viewModelScope` is already cancelled by the time `onCleared` runs, so a stop report
         * launched there would be dropped — taking the resume position and the ffmpeg kill with it.
         */
        fun reportStopDetached(
            source: PlaybackMediaSource,
            snapshot: PlaybackSnapshot,
        ) {
            detachedScope.launch { reportStop(source, snapshot) }
        }

        /**
         * Membership and [playSessionId] are passed in rather than read from [SyncPlayStatusHolder]:
         * by the time a group ending can be observed, `inGroup` is already `false` and the holder's
         * id has been cleared by the controller's teardown.
         *
         * Server-only: playback carries on solo, so the progress ticker keeps writing the position.
         */
        suspend fun reportGroupExitStop(
            source: PlaybackMediaSource,
            snapshot: PlaybackSnapshot,
            playSessionId: String?,
        ) {
            if (source !is LocalPlaybackMediaSource) return
            val target = source.serverTarget(inGroup = true)?.copy(playSessionId = playSessionId) ?: return
            sendStopReport(target, snapshot.positionTicks)
        }

        /**
         * Remote sources only, group or no group: a [LocalPlaybackMediaSource] is direct play off
         * this device's storage, so there is no ffmpeg process anywhere to kill.
         */
        @Suppress(
            "ReturnCount",
        )
        suspend fun stopTranscoding(source: PlaybackMediaSource) {
            if (source !is RemotePlaybackMediaSource) return
            val target = source.serverTarget() ?: return
            if (target.playMethod != PlayMethod.TRANSCODE) return
            val playSessionId = target.playSessionId ?: return

            val deviceId = api.deviceId
            if (deviceId == null) {
                Timber.w("No device id; cannot stop the encoding process for %s", playSessionId)
                return
            }
            runReport("stopEncoding") {
                api.stopEncodingProcess(deviceId = deviceId, playSessionId = playSessionId)
            }
        }

        /**
         * [currentSource] and [snapshot] are read fresh on every tick rather than captured, so the
         * loop survives a re-resolve without being torn down and restarted.
         */
        fun startReporting(
            scope: CoroutineScope,
            currentSource: () -> PlaybackMediaSource?,
            snapshot: () -> PlaybackSnapshot,
        ): Job =
            scope.launch {
                while (true) {
                    delay(PROGRESS_INTERVAL)
                    val source = currentSource() ?: continue
                    reportProgress(source, snapshot())
                }
            }

        /**
         * A parallel entry point rather than a `PlaybackMediaSource`: a queue entry carries no track
         * selections, no live stream and no resume negotiation, and it *does* carry the queue's
         * repeat and shuffle modes. The plumbing underneath is shared, so the two cannot drift.
         */
        suspend fun reportMusicStart(
            target: MusicReportTarget,
            positionTicks: Long,
            isPaused: Boolean,
            repeatMode: RepeatMode,
            playbackOrder: PlaybackOrder,
        ) {
            val server = target.serverTarget(positionTicks) ?: return
            runReport("music start") {
                api.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = server.itemId,
                        playMethod = server.playMethod.toSdk(),
                        playSessionId = server.playSessionId,
                        mediaSourceId = server.mediaSourceId,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = positionTicks,
                        repeatMode = repeatMode,
                        playbackOrder = playbackOrder,
                    ),
                )
            }
        }

        /** The local write-through is unconditional, exactly as it is for video. */
        suspend fun reportMusicProgress(
            target: MusicReportTarget,
            positionTicks: Long,
            isPaused: Boolean,
            repeatMode: RepeatMode,
            playbackOrder: PlaybackOrder,
        ) {
            target.serverTarget(positionTicks)?.let { server ->
                runReport("music progress") {
                    api.reportPlaybackProgress(
                        PlaybackProgressInfo(
                            itemId = server.itemId,
                            playMethod = server.playMethod.toSdk(),
                            playSessionId = server.playSessionId,
                            mediaSourceId = server.mediaSourceId,
                            isPaused = isPaused,
                            isMuted = false,
                            canSeek = true,
                            positionTicks = positionTicks,
                            repeatMode = repeatMode,
                            playbackOrder = playbackOrder,
                        ),
                    )
                }
            }
            userDataRepository.setPosition(target.itemId.toString(), positionTicks)
        }

        /**
         * Symmetrical with [reportStop]: a track that *finished* is marked played and reported at its
         * full runtime, one left mid-way keeps its position.
         */
        suspend fun reportMusicStop(
            target: MusicReportTarget,
            positionTicks: Long,
            hasEnded: Boolean,
        ) {
            val reportedTicks = if (hasEnded) target.runTimeTicks else positionTicks
            target.serverTarget(reportedTicks)?.let { sendStopReport(it, reportedTicks) }
            stopMusicTranscoding(target)
            if (hasEnded) {
                userDataRepository.setPlayed(target.itemId.toString(), played = true)
            } else {
                userDataRepository.setPosition(target.itemId.toString(), reportedTicks)
            }
        }

        private suspend fun stopMusicTranscoding(target: MusicReportTarget) {
            if (target.playMethod != PlayMethod.TRANSCODE) return
            val playSessionId = target.playSessionId ?: return
            val deviceId = api.deviceId
            if (deviceId == null) {
                Timber.w("No device id; cannot stop the encoding process for %s", playSessionId)
                return
            }
            runReport("music stopEncoding") {
                api.stopEncodingProcess(deviceId = deviceId, playSessionId = playSessionId)
            }
        }

        /**
         * The same two rules the video path applies. A music queue is never in a SyncPlay group —
         * that combination is refused outright — so the group exception has no analogue here.
         */
        private fun MusicReportTarget.serverTarget(positionTicks: Long): ServerReportTarget? {
            if (playSessionId == null) return null
            if (!connectionState.state.value.isOnline) {
                Timber.d("Offline; skipping the server report for track %s", itemId)
                return null
            }
            return ServerReportTarget(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                playMethod = playMethod,
                playSessionId = playSessionId,
                liveStreamId = null,
                selectedAudioIndex = null,
                selectedSubtitleIndex = null,
                startPositionTicks = positionTicks,
            )
        }

        private suspend fun sendStopReport(
            target: ServerReportTarget,
            positionTicks: Long?,
        ) = runReport("stop") {
            api.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = target.itemId,
                    positionTicks = positionTicks,
                    playSessionId = target.playSessionId,
                    liveStreamId = target.liveStreamId,
                    mediaSourceId = target.mediaSourceId,
                    failed = false,
                ),
            )
        }

        /**
         * @param inGroup overridable for exactly one caller, [reportGroupExitStop]; every other path
         *   asks the holder.
         */
        private fun PlaybackMediaSource.serverTarget(inGroup: Boolean = syncPlay.inGroup.value): ServerReportTarget? {
            if (!connectionState.state.value.isOnline) {
                Timber.d("Offline; skipping the server report for %s", itemId)
                return null
            }
            return when (this) {
                is RemotePlaybackMediaSource -> toTarget(playSessionId, liveStreamId)

                is LocalPlaybackMediaSource ->
                    if (inGroup) {
                        // Minted by SyncPlayLocalSession; `null` (a failed mint) still reports.
                        toTarget(syncPlay.mintedPlaySessionId.value, liveStreamId = null)
                    } else {
                        Timber.d("Playing %s locally and alone; nothing to report to the server", itemId)
                        null
                    }
            }
        }

        private fun PlaybackMediaSource.toTarget(
            playSessionId: String?,
            liveStreamId: String?,
        ): ServerReportTarget =
            ServerReportTarget(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                playMethod = playMethod,
                playSessionId = playSessionId,
                liveStreamId = liveStreamId,
                selectedAudioIndex = selectedAudioIndex,
                selectedSubtitleIndex = selectedSubtitleIndex,
                startPositionTicks = startPositionTicks,
            )

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
            /** What jellyfin-web sends, and roughly what the server expects. */
            val PROGRESS_INTERVAL = 5.seconds
        }
    }

/**
 * @param playSessionId `null` for a downloaded track, which reports nothing and has no encoder.
 * @param runTimeTicks the position a *completed* track's stop report carries. From the item, not the
 *   player: an HLS transcode's duration is an estimate until its last segment lands.
 */
data class MusicReportTarget(
    val itemId: UUID,
    val mediaSourceId: String,
    val playMethod: PlayMethod,
    val playSessionId: String?,
    val runTimeTicks: Long,
)

/**
 * @param playSessionId `null` when a local file's mint failed; the server keys the session on the
 *   authenticated device, so a report without an id still lands.
 */
private data class ServerReportTarget(
    val itemId: UUID,
    val mediaSourceId: String,
    val playMethod: PlayMethod,
    val playSessionId: String?,
    val liveStreamId: String?,
    val selectedAudioIndex: Int?,
    val selectedSubtitleIndex: Int?,
    val startPositionTicks: Long,
)

private fun PlayMethod.toSdk(): org.jellyfin.sdk.model.api.PlayMethod =
    when (this) {
        PlayMethod.DIRECT_PLAY -> org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY
        PlayMethod.DIRECT_STREAM -> org.jellyfin.sdk.model.api.PlayMethod.DIRECT_STREAM
        PlayMethod.TRANSCODE -> org.jellyfin.sdk.model.api.PlayMethod.TRANSCODE
    }
