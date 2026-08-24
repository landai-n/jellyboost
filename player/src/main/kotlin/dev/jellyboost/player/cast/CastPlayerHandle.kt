package dev.jellyboost.player.cast

import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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

        /** Permanently `null`, not a "before the first prepare" state — see the class docs. */
        override val player: Player? = null

        /**
         * `forwardVideoSize = false`: `CastPlayer` reports `VideoSize.UNKNOWN` throughout, so
         * forwarding it would overwrite a good aspect ratio with nothing.
         */
        private val listener =
            playerEventListener(
                emit = { _events.tryEmit(it) },
                forwardVideoSize = false,
                errorLogPrefix = "Cast playback error",
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
            val castSpec = specMapper.map(spec, remote, metadata.metadataFor(spec.mediaId))
            loaded = castSpec
            Timber.d("Casting %s as %s", castSpec.mediaId, castSpec.contentType)
            with(player) {
                setMediaItem(castSpec.toMediaItem(), startPositionMs.coerceAtLeast(0L))
                this.playWhenReady = playWhenReady
                prepare()
            }
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
         * URL as their id. A natural finish is exempt: that reading marks the item watched.
         */
        override fun snapshot(): PlaybackSnapshot {
            val current = castPlayer ?: return PlaybackSnapshot(isValid = false)
            val ended = current.playbackState == Player.STATE_ENDED
            if (!ended && !current.holdsLoadedItem()) return PlaybackSnapshot(isValid = false)
            return PlaybackSnapshot(
                positionMs = current.currentPosition.coerceAtLeast(0L),
                durationMs = current.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                bufferedMs = current.bufferedPosition.coerceAtLeast(0L),
                isPlaying = current.isPlaying,
                hasEnded = ended,
            )
        }

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

        override fun stop() {
            castPlayer?.run {
                stop()
                clearMediaItems()
            }
            loaded = null
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
