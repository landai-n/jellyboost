package dev.jellyboost.player.music

import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.session.ExoPlayerHandle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MusicPlayerPort] over the shared `ExoPlayer`, using its **native playlist**: the media session
 * derives the notification's previous/next buttons from the wrapped player's playlist commands, so a
 * real `setMediaItems` is the whole of "the notification can skip tracks".
 *
 * The player is shared with the video path, so [claim] must put back everything it changes: its own
 * listener, and `AUDIO_CONTENT_TYPE_MUSIC` in place of `MOVIE`. The attribute flip passes
 * `handleAudioFocus = true` so focus is re-requested under the new attributes.
 */
@UnstableApi
@Singleton
internal class ExoMusicPlayerAdapter
    @Inject
    constructor(
        private val playerHandle: ExoPlayerHandle,
    ) : MusicPlayerPort {
        private val _events =
            MutableSharedFlow<MusicPlayerEvent>(
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        override val events: Flow<MusicPlayerEvent> = _events.asSharedFlow()

        private var claimed = false

        /**
         * The exact instance [claim] configured. `ExoPlayerHandle.release()` destroys the player and
         * `requirePlayer()` silently builds a fresh one, so `claimed` alone would stay `true` against
         * a player the claim never touched. Identity is checked on every [player] resolution.
         */
        private var claimedPlayer: Player? = null

        private val listener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    _events.tryEmit(
                        MusicPlayerEvent.ItemTransition(
                            index = player().currentMediaItemIndex,
                            mediaId = mediaItem?.mediaId,
                            // Only an automatic transition means the previous track finished.
                            automatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        ),
                    )
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _events.tryEmit(MusicPlayerEvent.IsPlayingChanged(isPlaying))
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) _events.tryEmit(MusicPlayerEvent.Ended)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Music playback error %d", error.errorCode)
                    _events.tryEmit(MusicPlayerEvent.Error(error.errorCode, error.message))
                }
            }

        override fun setQueue(
            entries: List<MusicQueueEntry>,
            startIndex: Int,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            claim()
            with(player()) {
                setMediaItems(
                    entries.map { it.toMediaItem() },
                    startIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
                    startPositionMs.coerceAtLeast(0L),
                )
                this.playWhenReady = playWhenReady
                prepare()
            }
        }

        override fun play() {
            player().play()
        }

        override fun pause() {
            player().pause()
        }

        override fun seekTo(positionMs: Long) {
            player().seekTo(positionMs.coerceAtLeast(0L))
        }

        override fun next() {
            player().seekToNextMediaItem()
        }

        /**
         * `seekToPrevious`, not `seekToPreviousMediaItem`: it restarts a track that is a few seconds
         * in, which is what the notification's own previous button does.
         */
        override fun previous() {
            player().seekToPrevious()
        }

        override fun seekToItem(index: Int) {
            with(player()) {
                if (index !in 0 until mediaItemCount) return
                seekTo(index, 0L)
            }
        }

        override fun removeItem(index: Int) {
            with(player()) {
                if (index !in 0 until mediaItemCount) return
                removeMediaItem(index)
            }
        }

        override fun moveItem(
            from: Int,
            to: Int,
        ) {
            with(player()) {
                if (from !in 0 until mediaItemCount || to !in 0 until mediaItemCount) return
                moveMediaItem(from, to)
            }
        }

        override fun setShuffleEnabled(enabled: Boolean) {
            player().shuffleModeEnabled = enabled
        }

        override fun setRepeatMode(mode: MusicRepeatMode) {
            player().repeatMode =
                when (mode) {
                    MusicRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    MusicRepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    MusicRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
        }

        override fun snapshot(): MusicPortSnapshot =
            with(player()) {
                MusicPortSnapshot(
                    currentItemIndex = currentMediaItemIndex.coerceAtLeast(0),
                    currentMediaId = currentMediaItem?.mediaId,
                    positionMs = currentPosition.coerceAtLeast(0L),
                    durationMs = duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                    isPlaying = isPlaying,
                    mediaItemCount = mediaItemCount,
                )
            }

        override fun retryPrepare() {
            player().prepare()
        }

        override fun release() {
            if (!claimed) return
            val player = player() // Resolving may clear a claim the handle rebuilt out from under.
            if (!claimed) return
            claimed = false
            claimedPlayer = null
            with(player) {
                // The listener goes *first*: `stop()`/`clearMediaItems()` fire it synchronously, and
                // that echo would reset state the new owner is about to build on.
                removeListener(listener)
                stop()
                clearMediaItems()
                setAudioAttributes(audioAttributes(C.AUDIO_CONTENT_TYPE_MOVIE), true)
            }
            Timber.d("Music released the shared player")
        }

        override fun stopAndRelease() {
            release()
            playerHandle.stopPlaybackService()
        }

        /** Idempotent: every transport call is allowed to assume the player is already claimed. */
        private fun claim() {
            val player = player() // Clears a stale claim first if the instance changed underneath.
            if (claimed) return
            claimed = true
            claimedPlayer = player
            playerHandle.startPlaybackService()
            with(player) {
                addListener(listener)
                setAudioAttributes(audioAttributes(C.AUDIO_CONTENT_TYPE_MUSIC), true)
            }
            Timber.d("Music claimed the shared player")
        }

        /** Identity-checked (see [claimedPlayer]): a rebuilt instance is treated as unclaimed. */
        private fun player(): Player {
            val current = playerHandle.requirePlayer()
            if (claimed && current !== claimedPlayer) {
                Timber.i("The shared player was rebuilt underneath the music claim; treating it as unclaimed")
                claimed = false
                claimedPlayer = null
            }
            return current
        }

        private fun audioAttributes(contentType: Int): AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(contentType)
                .build()

        private companion object {
            const val EVENT_BUFFER = 16
        }
    }

/**
 * The metadata here is the only source the notification and lock screen have — the session reads
 * `MediaItem.mediaMetadata` off the timeline.
 *
 * A transcoded entry must name its mime type: the universal URL has no `.m3u8` segment to infer
 * from, so without the hint `DefaultMediaSourceFactory` hands an HLS playlist to the progressive
 * extractors. Direct play and `file://` are left to content sniffing.
 */
private fun MusicQueueEntry.toMediaItem(): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(mediaId)
        .setUri(uri.toUri())
        .apply { if (playMethod == PlayMethod.TRANSCODE) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(albumTitle)
                .setArtworkUri(artworkUri?.toUri())
                .setTrackNumber(trackNumber)
                .setDiscNumber(discNumber)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        ).build()
