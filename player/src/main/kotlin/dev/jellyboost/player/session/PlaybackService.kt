package dev.jellyboost.player.session

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.player.R
import dev.jellyboost.player.music.MusicSessionCallback
import dev.jellyboost.player.syncplay.SyncPlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Hosts the media session for the shared [ExoPlayerHandle] player, which it does *not* own —
 * `PlayerViewModel` drives the same instance directly.
 *
 * The session is built on [SyncPlayAwareForwardingPlayer], never on the raw player: the
 * notification and headset buttons dispatch through the session without passing `PlayerViewModel`,
 * and in a SyncPlay group that would move this member alone and break the group.
 *
 * [addSession] must be called explicitly. Media3 only starts managing a session — notification,
 * foreground promotion — once it has been added, and normally connecting a `MediaController` does
 * that; no controller ever connects here, so without the call the service stays an ordinary
 * background service the platform stops as soon as the app leaves the foreground.
 */
@UnstableApi
@AndroidEntryPoint
internal class PlaybackService :
    MediaSessionService(),
    MediaSessionService.Listener {
    @Inject
    internal lateinit var playerHandle: ExoPlayerHandle

    /** Published so the SyncPlay presence service stands aside while this service is up. */
    @Inject
    internal lateinit var serviceState: PlaybackServiceState

    @Inject
    internal lateinit var syncPlayController: SyncPlayController

    /**
     * Given to the session unconditionally, video sessions included: with nothing musical loaded it
     * contributes an empty button list and two commands nobody sends.
     */
    @Inject
    internal lateinit var musicSessionCallback: MusicSessionCallback

    @Inject
    internal lateinit var musicController: MusicController

    private var mediaSession: MediaSession? = null

    /**
     * `Main` because `setMediaButtonPreferences` is a session call and Media3 requires the
     * session's own thread; cancelled in [onDestroy] before the session is released.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        serviceState.setRunning(true)
        val sessionPlayer = SyncPlayAwareForwardingPlayer(playerHandle.requirePlayer(), syncPlayController)
        val session =
            MediaSession
                .Builder(this, sessionPlayer)
                .setCallback(musicSessionCallback)
                .apply { launchIntent()?.let(::setSessionActivity) }
                .build()
        mediaSession = session
        // Distinct on the *derived button list*, not the state: the state ticks every second with
        // the playback position, and identical buttons re-stamped per tick are sixty pointless
        // notification updates a minute (`CommandButton` has value equality).
        serviceScope.launch {
            musicController.state
                .map(musicSessionCallback::buttonsFor)
                .distinctUntilChanged()
                .collect { buttons ->
                    session.setMediaButtonPreferences(buttons)
                }
        }
        // Without the small icon the status bar shows Media3's generic one.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_fin) },
        )
        // Required: nothing else adds the session — see the class documentation.
        addSession(session)
        setListener(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * A `null` intent is a `START_STICKY` restart after a process kill, with nothing to resume:
     * carrying on would build an ExoPlayer and a `MediaSession` for no media.
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
     * Built from the launcher intent rather than a `MainActivity` reference so `:player` keeps no
     * dependency on `:app`; the activity is `singleTop`, so this re-uses the running task.
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

    /** Overridden because the default behaviour on a refused foreground promotion is a crash. */
    @RequiresApi(android.os.Build.VERSION_CODES.S)
    override fun onForegroundServiceStartNotAllowedException() {
        Timber.w("Android refused to promote the playback service to the foreground")
    }

    /**
     * Media3's default keeps the service alive so a paused session can be resumed from the
     * notification; with nothing playing that is a stuck notification.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /**
     * Order matters throughout. The session is released before the player it was built around;
     * the running flag is cleared *before* [ExoPlayerHandle.release], because while it is set that
     * call defers back to this teardown and would no-op.
     */
    override fun onDestroy() {
        Timber.d("Releasing the playback media session")
        // Before the session is released: the collector below writes to it.
        serviceScope.cancel()
        clearListener()
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
        serviceState.setRunning(false)
        playerHandle.release()
        super.onDestroy()
    }
}
