package dev.jellyfinnative.player.session

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.jellyfinnative.player.syncplay.SyncPlayController
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
 *
 * ### Why the session does not get the player itself (M11)
 * The notification, headset buttons and every other media-button surface dispatch through the
 * session, which is a transport path that never touches `PlayerViewModel` — so in a SyncPlay group
 * they moved this member's player alone and broke the group. The session is therefore built on
 * [SyncPlayAwareForwardingPlayer], which turns in-group transport into requests to the server and is
 * a plain pass-through otherwise.
 *
 * ### Why [addSession] is called explicitly (M9)
 * Media3 only starts managing a session — posting the notification, promoting the service to the
 * foreground — once the session has been *added* to the service. In the canonical sample that
 * happens implicitly, because the UI reaches the player through a `MediaController` and connecting
 * one is what triggers [onGetSession] and the add. This app deliberately drives the shared
 * `ExoPlayer` directly instead, so no controller ever connects, so nothing ever added the session:
 * the service stayed an ordinary background service with no notification, and the first time the
 * app left the foreground the platform stopped it and playback with it. That is the root cause of
 * the "backgrounding the app pauses playback" issue carried since M5 — not the notification
 * permission, which only decides whether the notification is *visible*.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService :
    MediaSessionService(),
    MediaSessionService.Listener {
    @Inject
    internal lateinit var playerHandle: ExoPlayerHandle

    /**
     * Published so the SyncPlay presence service knows to stand aside while this one is up — one
     * foreground service holding the process's network is enough (DECISIONS.md 2026-07-31).
     */
    @Inject
    internal lateinit var serviceState: PlaybackServiceState

    /**
     * Only ever consulted through [SyncPlayAwareForwardingPlayer] — the session is the one transport
     * surface that does not go through `PlayerViewModel`, and a group's transport is requests.
     */
    @Inject
    internal lateinit var syncPlayController: SyncPlayController

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        serviceState.setRunning(true)
        // The session gets the group-aware wrapper; everything else in the app keeps driving the
        // shared player directly. See [SyncPlayAwareForwardingPlayer].
        val sessionPlayer = SyncPlayAwareForwardingPlayer(playerHandle.requirePlayer(), syncPlayController)
        val session =
            MediaSession
                .Builder(this, sessionPlayer)
                .apply { launchIntent()?.let(::setSessionActivity) }
                .build()
        mediaSession = session
        // The line that keeps playback alive in the background — see the class documentation.
        addSession(session)
        setListener(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * The system restarting this service after the process was killed (audit STAB-11).
     *
     * A `null` intent means exactly that — a `START_STICKY` restart with nothing to resume, not a
     * real caller — so promoting to the foreground here would build an ExoPlayer and a
     * `MediaSession` with nothing to play. Stopping immediately and asking not to be restarted
     * again is cheaper than a notification for a session nobody asked to resume.
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Tapping the media notification comes back to the app.
     *
     * Built from the launcher intent rather than from a direct `MainActivity` reference so that
     * `:player` keeps no dependency on `:app`; the activity is `singleTop`, so this re-uses the
     * running task and the player screen is still on it, at the position playback has reached.
     */
    private fun launchIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    /**
     * The system refused the foreground promotion (API 31+).
     *
     * Rare — it needs the app to have been backgrounded before playback was ever started — but the
     * default behaviour is an uncaught exception, and a crash is a far worse outcome than a session
     * that plays without a notification.
     */
    @RequiresApi(android.os.Build.VERSION_CODES.S)
    override fun onForegroundServiceStartNotAllowedException() {
        Timber.w("Android refused to promote the playback service to the foreground")
    }

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

    /**
     * Service teardown, and the second of the two paths that release the shared player (STAB-05).
     *
     * The session goes first: it was built around the player, and Media3 unwinds its own listeners
     * through it. Releasing the player is then safe and, unlike the ViewModel's own teardown, always
     * reached — the service is stopped from `ExoPlayerHandle.stop()` and by a swipe-away, including
     * the cases where no player screen is left to clear.
     */
    override fun onDestroy() {
        Timber.d("Releasing the playback media session")
        clearListener()
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
        playerHandle.release()
        serviceState.setRunning(false)
        super.onDestroy()
    }
}
