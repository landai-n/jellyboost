package dev.jellyfinnative.player.session

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Hosts the media session for the shared [ExoPlayerHandle] player.
 *
 * Its job is everything that has to keep happening once the player screen is no longer on top:
 * the foreground-service promotion that stops Android from killing playback, the media
 * notification with its transport controls, and the media button / audio-focus plumbing that
 * `MediaSessionService` provides for free.
 *
 * It deliberately does *not* own the player — the same instance is driven directly by
 * `PlayerViewModel` (DECISIONS.md, 2026-07-28). Consequently the session is a thin wrapper and
 * this class has no playback logic of its own.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject
    internal lateinit var playerHandle: ExoPlayerHandle

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, playerHandle.requirePlayer()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * The user swiped the app away while nothing was playing.
     *
     * Media3's default is to keep the service alive so a paused session can be resumed from the
     * notification; with nothing playing that is just a stuck notification, so the service stops.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Timber.d("Releasing the playback media session")
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
