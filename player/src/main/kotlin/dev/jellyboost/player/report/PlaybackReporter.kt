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
 * Keeps the server's idea of this session in step with the player's.
 *
 * Three things depend on it, and all three are visible to the user:
 * "now playing" in the server dashboard, the resume position on every other client, and — for a
 * transcode — whether an ffmpeg process is still running after we leave.
 *
 * Modelled on jellyfin-android's `PlayerViewModel.kt:410-562`, with one addition: every position
 * that goes to the server is **also** written locally through [UserDataRepository], so resume
 * behaves identically whether the item was streamed or played from a download.
 *
 * ### The offline half
 * The server half of every method is skipped entirely when there is nothing to tell —
 * see [serverTarget]. Two situations qualify, and they are one rule:
 *
 * - the source is a [LocalPlaybackMediaSource] **played on its own**: it has no `playSessionId`, so
 *   a start/progress/stop triad keyed on it would be meaningless even if the server were reachable,
 *   and there is no encoder to kill;
 * - [ConnectionStateProvider] says we are offline: every call would burn a connect timeout per
 *   five-second tick before failing, filling the log with warnings for reports the server will
 *   never see anyway.
 *
 * The **local** write is not conditional. `UserDataRepository.setPosition` stores the row with
 * `toBeSynced = true` regardless of the network (it only clears the flag on a successful push), so
 * an airplane-mode session accumulates exactly the pending rows `UserDataSyncWorker` drains on
 * reconnect.
 *
 * ### The one exception
 * A local file **in a SyncPlay group** does report, whenever the server is reachable. The others
 * are watching this device's playback whether or not its bytes came from the server, and a member
 * missing from the dashboard is a member nobody can see stalling. `SyncPlayLocalSession` mints a
 * play session id for it through one `PlaybackInfo` POST and publishes it on
 * [SyncPlayStatusHolder]; a `null` id still reports, because the server keys the session on the
 * authenticated device.
 *
 * What the exception does *not* extend to is [stopTranscoding]: a file on disk never started an
 * encoder, so there is nothing to kill and the call would only be a lie to the server.
 */
@Singleton
internal class PlaybackReporter
    @Inject
    constructor(
        private val api: PlayerApi,
        private val userDataRepository: UserDataRepository,
        private val connectionState: ConnectionStateProvider,
        @DetachedPlayerScope private val detachedScope: CoroutineScope,
        /**
         * Group membership, read on every report.
         *
         * Defaulted so that constructing a reporter with nothing to do with SyncPlay — which is
         * what every test of the solo paths does — gets a holder that is never in a group, and
         * therefore exactly the solo behaviour. Hilt always passes the singleton.
         */
        private val syncPlay: SyncPlayStatusHolder = SyncPlayStatusHolder(),
    ) {
        /**
         * Playback started (or restarted after a re-resolve).
         *
         * [repeatMode] and [playbackOrder] are defaulted to what a film always is, so the video
         * path and every test of it need not name them; the music queue passes its own.
         */
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
         * One progress tick.
         *
         * The local write happens even when the server call fails, is skipped, or could not have
         * been made — an unreachable server must not cost the user their place in the film.
         *
         * A tick whose snapshot is not [valid][PlaybackSnapshot.isValid] is skipped entirely: the
         * player no longer holds this source (a receiver unloaded from the television, or another
         * sender took it), so its position is some other session's — writing it would reset or
         * corrupt this item's resume position.
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
         * Playback stopped.
         *
         * Finishing the item marks it watched through [UserDataRepository] rather than through a
         * bare `markPlayedItem` call, so the local row, the event bus and the server all agree
         * without a second round trip — and offline it is the *only* thing that happens, which is
         * exactly what leaves a pending row for the sync worker. A transcode additionally has its
         * encoding process killed; skipping that is what leaves stray ffmpeg processes on the
         * server.
         *
         * A stop whose snapshot is not [valid][PlaybackSnapshot.isValid] still closes the server
         * session and kills the encoder — both are about the *session* — but carries no position and
         * writes nothing locally: the reading is not this source's, and the resume position the
         * progress ticker last recorded is the honest one.
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
         * Reports the stop from a scope that outlives the screen.
         *
         * `viewModelScope` is already cancelled by the time `onCleared` runs, so a stop report
         * launched there would be dropped — and with it the resume position and the ffmpeg kill.
         * The detached [SupervisorJob][DetachedPlayerScope] scope is the whole point, and it matters
         * just as much offline: the local position write is the only record of where the user got to.
         */
        fun reportStopDetached(
            source: PlaybackMediaSource,
            snapshot: PlaybackSnapshot,
        ) {
            detachedScope.launch { reportStop(source, snapshot) }
        }

        /**
         * Closes the server's view of a downloaded item that was playing as part of a group.
         *
         * Leaving a group mid-film does not stop playback — it continues solo, off the same file —
         * but it does end the session the server was told about, and the reports stop with it. Sent
         * once, on the way out, so the dashboard does not keep showing this device frozen at the
         * position it left at.
         *
         * It takes the membership as given rather than reading [SyncPlayStatusHolder]: by the time
         * anything can observe a group ending, `inGroup` is already `false` and every ordinary
         * report path has correctly gone quiet. [playSessionId] is likewise passed in, because the
         * holder's copy is cleared by the controller's own teardown.
         *
         * Deliberately server-only: playback carries on, so the position keeps being written
         * locally by the progress ticker and writing a "stopped" one here would be false.
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
         * Kills the server-side encoder if, and only if, this source is being transcoded.
         *
         * Remote sources only, group or no group: a [LocalPlaybackMediaSource] is direct play off
         * this device's storage, so there is no ffmpeg process anywhere to kill.
         */
        @Suppress(
            // Guard chain over what a stop report needs; a missing piece means don't report, not report empty.
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
         * Starts the 5-second progress ticker on [scope].
         *
         * [currentSource] and [snapshot] are read fresh on every tick rather than captured, so the
         * loop keeps reporting correctly across a re-resolve (a quality change or a fallback
         * retry) without being torn down and restarted.
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
         * A music track started.
         *
         * A parallel entry point rather than a `PlaybackMediaSource` because a queue entry is a
         * genuinely different thing: it carries no track selections, no live stream and no resume
         * negotiation, and it *does* carry the queue's repeat and shuffle modes, which a film
         * never has. The plumbing underneath is the same — the private [ServerReportTarget] and
         * [runReport] — so the two paths cannot drift apart in what they send.
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

        /**
         * One music progress tick.
         *
         * The local write-through is unconditional, exactly as it is for video: it is what makes
         * "Continue Listening" resume at the right place whether the track was streamed, played
         * from a download, or played in airplane mode.
         */
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
         * A music track stopped — skipped away from, or played to the end.
         *
         * Symmetrical with [reportStop]: a track that *finished* is marked played and reported at
         * its full runtime, and one that was left mid-way keeps its position. Jellyfin marks audio
         * played on the server's own stop handling; the local write is what makes the two agree
         * offline, and it is the only thing that happens there.
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

        /** Kills the encoder behind a transcoded queue entry; a no-op for anything else. */
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
         * The queue entry as a server session, or `null` when there is none to tell.
         *
         * The same two rules the video path applies, for the same reasons: a downloaded track has
         * no play session (nothing was negotiated, so there is nothing to key one on), and offline
         * every call would burn a connect timeout per tick. A music queue is never in a SyncPlay
         * group — that combination is refused outright — so the group exception has no analogue
         * here.
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
         * The source as a session the server should be told about, or `null` when there is none.
         *
         * Returning a target rather than a boolean is what lets every call site reach the session
         * id and the stream indices without a cast — and what lets the two kinds of
         * reportable source (a stream, and a downloaded file being watched with a group) produce
         * the same report from different material.
         *
         * @param inGroup overridable for exactly one caller, [reportGroupExitStop]; every other
         *   path asks the holder.
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
                        // Minted by SyncPlayLocalSession; `null` when the mint failed, which still
                        // reports.
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
            /** Matches what jellyfin-web and jellyfin-android send; the server expects roughly this. */
            val PROGRESS_INTERVAL = 5.seconds
        }
    }

/**
 * One music queue entry, as the server needs to hear about it.
 *
 * Deliberately not a `PlaybackMediaSource`: that type carries a resolved *film* — track lists,
 * external subtitles, a live stream id, trickplay — none of which a track has, and half of which
 * would have to be filled with lies. This is the eight fields the three music reports need.
 *
 * @param playSessionId `null` for a downloaded track; nothing is reported for it and there is no
 *   encoder to stop, which is the rule for any local file played alone.
 * @param runTimeTicks the position a *completed* track's stop report carries. Taken from the item
 *   rather than from the player, because an HLS transcode's duration is an estimate until its last
 *   segment lands.
 */
data class MusicReportTarget(
    val itemId: UUID,
    val mediaSourceId: String,
    val playMethod: PlayMethod,
    val playSessionId: String?,
    val runTimeTicks: Long,
)

/**
 * One playback session as the server sees it, whatever the bytes came from.
 *
 * The same six fields fill a start, a progress and a stop report, and building them here is what
 * keeps those three bodies identical for a stream, for a downloaded file being watched with a
 * group, and for a music queue entry.
 *
 * @param playSessionId nullable, which the SDK's DTOs already allow. A stream always has one; a
 *   local file has whatever the mint produced, and `null` when the mint failed — the server keys
 *   the session on the authenticated device, so a report without an id still lands.
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

/** Our [PlayMethod] is a copy of the SDK's; this is the one place the two meet. */
private fun PlayMethod.toSdk(): org.jellyfin.sdk.model.api.PlayMethod =
    when (this) {
        PlayMethod.DIRECT_PLAY -> org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY
        PlayMethod.DIRECT_STREAM -> org.jellyfin.sdk.model.api.PlayMethod.DIRECT_STREAM
        PlayMethod.TRANSCODE -> org.jellyfin.sdk.model.api.PlayMethod.TRANSCODE
    }
