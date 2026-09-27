package dev.jellyboost.player.cast

import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.PlayerHandle
import dev.jellyboost.player.session.playerEventFlow
import dev.jellyboost.player.session.playerEventListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [PlayerHandle] that drives a Cast receiver, over media3-cast's `CastPlayer`.
 *
 * A track selection returning `false` sends the caller back to the server to re-negotiate; on a
 * receiver that is the normal answer, not a failure — only the server can change the audio track
 * or burn in a subtitle the receiver cannot render.
 *
 * Deliberately no video surface ([player] is permanently `null`) and no `PlaybackService`: the Cast
 * framework publishes its own media session and notification.
 */
@Singleton
@UnstableApi
internal class CastPlayerHandle
    @Inject
    constructor(
        private val availability: CastAvailability,
        private val specMapper: CastSpecMapper,
        private val converter: CastMediaItemConverter,
        private val metadata: CastMetadataHolder,
    ) : PlayerHandle {
        private val _events = playerEventFlow()

        override val events: Flow<PlayerEvent> = _events.asSharedFlow()

        private var castPlayer: CastPlayer? = null

        /** The load currently on the receiver; its tracks are what a subtitle selection matches. */
        private var loaded: CastMediaSpec? = null

        /** The negotiated source behind [loaded], forgotten with it. */
        override var preparedSource: PlaybackMediaSource? = null
            private set

        /** Permanently `null`, not a "before the first prepare" state — see the class docs. */
        override val player: Player? = null

        /** Fed every reading this handle takes, from callbacks and from [snapshot] alike. */
        private val presence = RemoteItemPresence(emit = { _events.tryEmit(it) })

        /**
         * `forwardVideoSize = false`: `CastPlayer` reports `VideoSize.UNKNOWN` throughout, so
         * forwarding it would overwrite a good aspect ratio with nothing.
         */
        private val listener =
            playerEventListener(
                emit = { _events.tryEmit(it) },
                forwardVideoSize = false,
                errorLogPrefix = "Cast playback error",
                afterEvents = { checkPresence() },
            )

        /**
         * Built lazily and on the main thread: `CastPlayer` binds to the calling thread's looper,
         * and Hilt may construct this handle off it. Constructing it is also the first touch of a
         * `com.google.android.gms` class, so a device without Play services never gets this far.
         */
        private fun requirePlayer(): CastPlayer? {
            castPlayer?.let { return it }
            val context = availability.castContext
            if (context == null) {
                Timber.w("No CastContext; the cast player cannot be built")
                return null
            }
            return CastPlayer(context, converter).also {
                it.addListener(listener)
                castPlayer = it
            }
        }

        /** Casting needs the negotiated source; this overload can only fail, as a player error. */
        override fun prepare(
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            Timber.w("Cast prepare without a resolved source for %s", spec.mediaId)
            _events.tryEmit(PlayerEvent.Error(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, NO_SOURCE))
        }

        override fun prepare(
            source: PlaybackMediaSource,
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            val remote = source as? RemotePlaybackMediaSource
            if (remote == null) {
                Timber.w("Refusing to cast a local source for %s", source.itemId)
                _events.tryEmit(PlayerEvent.Error(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, LOCAL_SOURCE))
                return
            }
            val player = requirePlayer()
            if (player == null) {
                _events.tryEmit(PlayerEvent.Error(PlaybackException.ERROR_CODE_REMOTE_ERROR, NO_RECEIVER))
                return
            }

            // The metadata must be published before the open: a receiver is loaded once, and
            // metadata arriving afterwards could only be applied by loading it a second time.
            val mapped = specMapper.map(spec, remote, metadata.metadataFor(spec.mediaId))
            // Recorded before the load reaches the receiver: from here the handle's readings are this
            // source's, and a coordinator still holding an older one must stop taking them as its own.
            loaded = mapped.copy(autoplay = playWhenReady)
            preparedSource = remote
            presence.onLoad()
            Timber.d("Casting %s as %s", mapped.mediaId, mapped.contentType)
            player.loadOnReceiver(mapped, startPositionMs, playWhenReady)
        }

        override fun play() {
            castPlayer?.play()
        }

        override fun pause() {
            castPlayer?.pause()
        }

        override fun seekTo(positionMs: Long) {
            castPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        }

        /**
         * Valid **only while the receiver still holds our item**: after a Stop from the television
         * or a takeover by another sender, `CastPlayer` keeps answering — at zero, or at the other
         * app's position — and a ticker would write that over this item's resume position. The
         * `contentId` arm exists because the framework's round-trip rebuilds items with the content
         * URL as their id. A natural finish is exempt: that reading marks the item watched, and it is
         * the receiver's own `IDLE` / `FINISHED` status that says so ([finishedMedia]) — media3-cast
         * 1.9.0 never reports `STATE_ENDED`, and the finished item leaves the queue like a stopped one.
         */
        override fun snapshot(): PlaybackSnapshot {
            val current = castPlayer ?: return PlaybackSnapshot(isValid = false)
            return current.reading().also { presence.onReading(it, ready = current.isReadyOrEnded()) }
        }

        /**
         * Also asked at the end of every callback batch, so a receiver dropping the item is noticed
         * when it says so rather than at the next progress tick.
         */
        private fun checkPresence() {
            val current = castPlayer ?: return
            if (loaded == null) return
            presence.onReading(current.reading(), ready = current.isReadyOrEnded())
        }

        private fun CastPlayer.reading(): PlaybackSnapshot {
            // Once the session is torn down `RemoteCastPlayer` drops its client but keeps its timeline and
            // state (`setCastSession(null)` updates neither), so our item still looks held — at its last
            // reported position, zero after a receiver stop. Nothing it says then is live.
            val client = remoteMediaClient() ?: return PlaybackSnapshot(isValid = false)
            // Before the item check: a finished item is gone from the queue by the time it is read.
            val ended = presence.ended ?: client.finishedMedia()?.let(presence::onFinished)
            return when {
                ended != null -> ended
                !holdsLoadedItem() -> PlaybackSnapshot(isValid = false)
                else ->
                    PlaybackSnapshot(
                        positionMs = currentPosition.coerceAtLeast(0L),
                        durationMs = duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                        bufferedMs = bufferedPosition.coerceAtLeast(0L),
                        isPlaying = isPlaying,
                    )
            }
        }

        /**
         * `null` unless the receiver's own status is `IDLE` with idle reason `FINISHED`; then which media
         * it finished, matched against [loaded] as the load names it (its content id and URL are both the
         * stream URL). Read from `RemoteMediaClient` because `CastPlayer` maps that status to plain IDLE.
         */
        private fun RemoteMediaClient.finishedMedia(): ReceiverFinish? {
            val status =
                mediaStatus?.takeIf {
                    it.playerState == MediaStatus.PLAYER_STATE_IDLE && it.idleReason == MediaStatus.IDLE_REASON_FINISHED
                } ?: return null
            val info = status.mediaInfo
            val spec = loaded
            return when {
                info == null -> ReceiverFinish.UNNAMED
                spec != null && (info.contentId == spec.contentId || info.contentUrl == spec.contentId) ->
                    ReceiverFinish.LOADED_ITEM
                else -> ReceiverFinish.OTHER_ITEM
            }
        }

        /** `STATE_ENDED` never comes from media3-cast 1.9.0; the receiver's finish stands in for it. */
        private fun CastPlayer.isReadyOrEnded(): Boolean = playbackState == Player.STATE_READY || presence.ended != null

        /**
         * The receiver's intent, readable even while [snapshot] is not: the play/pause toggle must not
         * flip to "play" just because the receiver's reading is momentarily not ours.
         */
        override val playWhenReady: Boolean get() = castPlayer?.playWhenReady == true

        /**
         * Read from the receiver's own `PAUSED` status, never from `CastPlayer`: its `isPlaying` is
         * derived from the masked `playWhenReady` (`STATE_READY` covers both PLAYING and PAUSED), so
         * it reports playing in exactly the state this exists to detect. `PAUSED` excludes
         * buffering and loading, which a tap must still answer with pause. Accepted cost: a second
         * tap landing before the receiver has acknowledged a play resends play instead of pausing.
         */
        override val isSettledPaused: Boolean
            get() = castPlayer != null && remoteMediaClient()?.isPaused == true

        private fun CastPlayer.holdsLoadedItem(): Boolean {
            val spec = loaded ?: return false
            val currentId = currentMediaItem?.mediaId ?: return false
            return currentId == spec.mediaId || currentId == spec.contentId
        }

        /** Always `false`: only a server re-negotiation with an `audioStreamIndex` changes this. */
        override fun selectAudioTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int,
        ): Boolean = false

        /**
         * Goes through `RemoteMediaClient.setActiveMediaTracks` because media3-cast 1.9.0's
         * `RemoteCastPlayer.setTrackSelectionParameters` is an empty method — the Player-level API
         * would silently do nothing. The receiver's track ids *are* the Jellyfin stream indices, so
         * no translation is needed. `true` is claimed only against what the receiver reports
         * holding: `setActiveMediaTracks` against unloaded or replaced media is a silent no-op.
         */
        @Suppress(
            "ReturnCount",
        )
        override fun selectSubtitleTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int?,
        ): Boolean {
            val client = remoteMediaClient() ?: return false
            if (jellyfinIndex == null) {
                client.setActiveMediaTracks(NO_TRACKS).logRejection("clearing the cast subtitles")
                return true
            }
            val sideLoaded = loaded?.tracks.orEmpty().any { it.id == jellyfinIndex }
            if (!sideLoaded) return false
            val onReceiver =
                runCatching { client.mediaStatus?.mediaInfo?.mediaTracks }
                    .getOrNull()
                    .orEmpty()
                    .any { it.id == jellyfinIndex.toLong() }
            if (!onReceiver) {
                Timber.w("The receiver no longer offers subtitle track %d; re-negotiating", jellyfinIndex)
                return false
            }
            client
                .setActiveMediaTracks(longArrayOf(jellyfinIndex.toLong()))
                .logRejection("selecting cast subtitle track $jellyfinIndex")
            return true
        }

        private fun PendingResult<RemoteMediaClient.MediaChannelResult>.logRejection(what: String) {
            setResultCallback { result ->
                if (!result.status.isSuccess) {
                    Timber.w("The receiver rejected %s: %s", what, result.status)
                }
            }
        }

        /** Guarded, not attempted: an unavailable command on a `BasePlayer` logs an error per call. */
        override fun setPlaybackSpeed(speed: Float) {
            val player = castPlayer ?: return
            if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                Timber.d("The receiver does not support playback speed; leaving it at 1×")
                return
            }
            player.setPlaybackSpeed(speed)
        }

        override val supportsPlaybackSpeed: Boolean
            get() = castPlayer?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true

        /**
         * Forgets [loaded] *before* stopping: the stop's own callbacks would otherwise find the
         * loaded item gone and report it missing.
         */
        override fun stop() {
            loaded = null
            preparedSource = null
            presence.onLoad()
            castPlayer?.run {
                stop()
                clearMediaItems()
            }
        }

        /**
         * Idempotent: the field is cleared first, so a second caller finds nothing to do. The
         * listener must be removed explicitly — it is a strong reference from a `@Singleton` to a
         * flow that outlives every session. Releasing does **not** end the cast session.
         */
        override fun release() {
            val player = castPlayer ?: return
            castPlayer = null
            loaded = null
            preparedSource = null
            presence.onLoad()
            player.removeListener(listener)
            player.release()
            Timber.d("Released the cast player")
        }

        private fun remoteMediaClient(): RemoteMediaClient? =
            runCatching {
                availability.castContext
                    ?.sessionManager
                    ?.currentCastSession
                    ?.remoteMediaClient
            }.getOrNull()

        private companion object {
            val NO_TRACKS = longArrayOf()
            const val NO_SOURCE = "Casting needs the resolved source."
            const val LOCAL_SOURCE = "A downloaded file cannot be reached by a Cast receiver."
            const val NO_RECEIVER = "No Cast receiver is connected."
        }
    }
