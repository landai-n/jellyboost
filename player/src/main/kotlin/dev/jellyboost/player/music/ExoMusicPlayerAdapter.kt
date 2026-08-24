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
 * [MusicPlayerPort] over the shared `ExoPlayer`, using its **native playlist**.
 *
 * That choice is load-bearing twice. The media
 * session derives the notification's and the lock screen's previous/next buttons from the wrapped
 * player's playlist commands, so a real `setMediaItems` is the whole of "the notification can skip
 * tracks" — there is no notification-building code anywhere here. And the session's timeline *is*
 * the queue, which is exactly the shape a `MediaLibraryService`/Android Auto follow-up needs.
 *
 * ### Claiming and letting go
 * The player is shared with the video path, so this class touches only what it must and puts it
 * back. [claim] attaches this adapter's own `Player.Listener` — the shared handle's listener stays
 * attached and keeps feeding `PlayerEvent`, which is harmless because nothing collects it while
 * music is playing — and flips the audio attributes to
 * [C.AUDIO_CONTENT_TYPE_MUSIC][androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC]. [release] puts
 * `AUDIO_CONTENT_TYPE_MOVIE` back and detaches the listener. The flip is made on the live player
 * with `handleAudioFocus = true`, which re-requests focus under the new attributes.
 *
 * ### The service
 * Started through [ExoPlayerHandle]'s own best-effort helper rather than a second `startService`
 * here: that method already carries the API 26 background-start handling that a music queue
 * started from a widget or a headset button needs just as much as video does.
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
         * The exact instance [claim] configured.
         *
         * `ExoPlayerHandle.release()` (the playback service's teardown, a video session's own
         * teardown) destroys the player and `requirePlayer()` silently builds a fresh one — with
         * no listener of ours, movie audio attributes, and an empty playlist. A boolean alone
         * cannot see that: `claimed` would stay `true` against a player the claim never touched,
         * and every transport call would land in a configured-looking void. Identity is checked
         * on every [player] resolution; a mismatch resets the claim so the next [setQueue] runs
         * the full claim again (listener, attributes, service start).
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
                            // Only an *automatic* transition means the previous track finished.
                            // A seek, a queue edit or a fresh `setMediaItems` did not.
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
         * `seekToPrevious`, not `seekToPreviousMediaItem`: the first restarts a track that is more
         * than a few seconds in and steps back otherwise, which is what a previous button means
         * everywhere in music and what the notification's own button already does.
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
                // The listener goes *first*: `stop()`/`clearMediaItems()` fire it synchronously
                // (a playlist change is a media-item transition), and that echo arriving after
                // the controller considers itself relinquished would reset state the new owner
                // is about to build on.
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

        /**
         * The shared player, with the claim's identity check (see [claimedPlayer]): a rebuilt
         * instance is treated as unclaimed, so nothing here ever operates on a player believing
         * it configured it when it configured its predecessor.
         */
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
 * One queue entry as ExoPlayer sees it.
 *
 * The metadata is what the media notification and the lock screen draw, and it is the only place
 * they get it from — the session reads `MediaItem.mediaMetadata` off the timeline, so there is no
 * separate notification-building step anywhere.
 *
 * A transcoded entry names its mime type explicitly: the universal URL carries no `.m3u8` path
 * segment for ExoPlayer's inference to read, so without the hint `DefaultMediaSourceFactory`
 * builds a `ProgressiveMediaSource` and hands an HLS playlist to the progressive extractors —
 * unplayable. Direct-play URLs and `file://` downloads are left to content sniffing, which
 * handles every container in [MusicStreamResolver.DIRECT_CONTAINERS].
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
