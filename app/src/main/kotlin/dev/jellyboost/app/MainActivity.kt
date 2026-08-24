package dev.jellyboost.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.player.cast.CastAvailability
import dev.jellyboost.player.pip.PipController
import dev.jellyboost.player.pip.PipState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Single activity hosting the whole app.
 *
 * It holds the splash screen until session restore has answered, then hands the resulting
 * [SessionState] to [JellyboostApp], which picks the start destination from it.
 *
 * A `FragmentActivity` rather than a plain `ComponentActivity`, and for exactly one reason: the
 * Cast chooser is a `DialogFragment` and `MediaRouteButton` throws without a fragment manager to
 * show it in. No fragment is ever added by this app's own code.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /**
     * Whether leaving the app right now should float the video, and at what aspect ratio.
     *
     * The activity hosts every screen, so it cannot decide this itself — `:player` publishes it
     * (see [PipController]). Injected rather than reached through a ViewModel because the decision
     * has to be available in [onUserLeaveHint], which runs outside composition.
     */
    @Inject
    lateinit var pipController: PipController

    /**
     * The Cast stack, brought up once from here.
     *
     * `CastContext` is process-wide and has to be created from an Android context on the main
     * thread, which makes the single activity the natural — and the framework's own recommended —
     * place. Injected rather than reached through a ViewModel because there is nothing to observe
     * here: this activity only starts it, and the button observes what comes out.
     */
    @Inject
    lateinit var castAvailability: CastAvailability

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { viewModel.sessionState.value is SessionState.Unknown }
        // The app is dark-only by design (see JellyfinTheme) — the system bar icons must
        // always be light, regardless of the *system's* light/dark setting. enableEdgeToEdge()'s
        // default SystemBarStyle.auto() derives icon appearance from the system's night-mode
        // configuration, not from the app's own (always-dark) theme, so on a system in light mode it
        // draws dark (black) icons over the app's dark UI. SystemBarStyle.dark(...) pins both bars to
        // the dark appearance (light icons) unconditionally, matching the theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        observePictureInPictureReadiness()
        startCastStack()

        setContent {
            JellyfinTheme {
                NotificationPermissionRequest()
                val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
                JellyboostApp(sessionState = sessionState)
            }
        }
    }

    /**
     * Brings the Cast stack up — a no-op, silently, on a device without Google Play services, which
     * is the whole point (see [CastAvailability]).
     *
     * **Posted, not called inline.** A direct call in the middle of `onCreate` would put a binder
     * round trip to another process — the Play services check behind [CastAvailability]'s own
     * executor — on the critical path to the first frame, where every millisecond is one the user
     * spends looking at the splash screen. Nothing needs the answer until a screen with a cast
     * button is drawn. Posting keeps the first touch of a `com.google.android.gms` class — the
     * class loading, the singleton graph, the executor — out of `onCreate` entirely.
     *
     * Still on the **main thread**, and deliberately: [CastAvailability.initialize] is `@MainThread`
     * for its own guard, and `CastContext` is created from the main looper. `Dispatchers.Main`
     * rather than `lifecycleScope`'s `Main.immediate` for exactly the reason `launch` is used at all
     * here — `immediate` runs the body inline when it is already on the main thread, which would put
     * that class loading straight back into `onCreate`.
     */
    private fun startCastStack() {
        lifecycleScope.launch(Dispatchers.Main) { castAvailability.initialize(this@MainActivity) }
    }

    /**
     * Keeps the system's auto-enter flag in step with what the player is doing (API 31+).
     *
     * `setAutoEnterEnabled` is what produces the *seamless* transition users expect from a video
     * app: the system captures the window as the user swipes home, instead of the app scrambling to
     * enter picture-in-picture after the fact. Below API 31 there is no such thing, and
     * [onUserLeaveHint] does the same job with a visible hop.
     */
    private fun observePictureInPictureReadiness() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pipController.state.collect { state ->
                    runCatching { setPictureInPictureParams(state.toParams()) }
                        .onFailure { Timber.w(it, "Could not update the picture-in-picture parameters") }
                }
            }
        }
    }

    /**
     * The user is leaving — Home, or the task switcher.
     *
     * The API 31+ path has already been armed by [observePictureInPictureReadiness] and enters on
     * its own, so this only covers API 26–30. Guarded on the player's own state: without it, leaving
     * the app from the library grid would shrink a still image into a floating window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!pipController.state.value.canEnter) return
        runCatching { enterPictureInPictureMode(pipController.state.value.toParams()) }
            .onFailure { Timber.w(it, "Could not enter picture-in-picture") }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController.setInPictureInPicture(isInPictureInPictureMode)
    }

    /**
     * The player's state as picture-in-picture parameters.
     *
     * `setSeamlessResizeEnabled` (API 31+) tells the system the content is video and can be scaled
     * without a cross-fade, which is the difference between a smooth shrink and a flicker.
     */
    private fun PipState.toParams(): PictureInPictureParams =
        PictureInPictureParams
            .Builder()
            .apply {
                aspectRatio?.let { (width, height) -> setAspectRatio(Rational(width, height)) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(canEnter)
                    setSeamlessResizeEnabled(true)
                }
            }.build()
}

/**
 * Asks once for `POST_NOTIFICATIONS` on API 33+.
 *
 * The download queue runs as foreground work and its notification is the only place a transfer can
 * be paused or cancelled from outside the app. Without the permission the work still runs — the
 * promotion is what keeps it alive, not the notification being *visible* — but the user loses that
 * control surface, so it is worth one dialog.
 *
 * Declining is final and harmless: nothing here re-asks, and nothing depends on the answer.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Root composable: nothing is drawn while the session is still [SessionState.Unknown] (the
 * splash screen is up at that point), after which [AppScaffold] (NavHost + bottom navigation bar)
 * is created with a start destination that matches the session.
 */
@Composable
internal fun JellyboostApp(sessionState: SessionState) {
    if (sessionState is SessionState.Unknown) return

    // Captured once: the start destination must not change under a live NavHost when the session
    // later flips — sign-out is handled by navigating, not by rebuilding the graph.
    val startsSignedIn = remember { sessionState is SessionState.LoggedIn }

    AppScaffold(
        startsSignedIn = startsSignedIn,
        sessionState = sessionState,
    )
}
