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
import dev.jellyboost.core.ui.theme.resolvesDark
import dev.jellyboost.player.cast.CastAvailability
import dev.jellyboost.player.pip.PipController
import dev.jellyboost.player.pip.PipState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * A `FragmentActivity` rather than a plain `ComponentActivity` for exactly one reason: the Cast
 * chooser is a `DialogFragment` and `MediaRouteButton` throws without a fragment manager to show it
 * in. No fragment is ever added by this app's own code.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Injected rather than reached through a ViewModel: [onUserLeaveHint] runs outside composition. */
    @Inject
    lateinit var pipController: PipController

    /** `CastContext` is process-wide and must be created from an Android context on the main thread. */
    @Inject
    lateinit var castAvailability: CastAvailability

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { viewModel.sessionState.value is SessionState.Unknown }
        // Dark for the first frame, whatever the preference turns out to be: DataStore has not
        // answered here, and the splash it draws over is dark-locked in themes.xml because nothing
        // can read a preference before that window exists (docs/features/theme.md). `applyBarStyle`
        // corrects the icons as soon as the preference lands.
        applyBarStyle(dark = true)
        super.onCreate(savedInstanceState)

        observePictureInPictureReadiness()
        startCastStack()

        setContent {
            val theme by viewModel.themePreference.collectAsStateWithLifecycle()
            val dark = theme.mode.resolvesDark()
            LaunchedEffect(dark) { applyBarStyle(dark) }

            JellyfinTheme(themeMode = theme.mode, dynamicColor = theme.dynamicColor) {
                NotificationPermissionRequest()
                val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
                JellyboostApp(sessionState = sessionState)
            }
        }
    }

    /**
     * Never `SystemBarStyle.auto()`: it derives icon appearance from the *system's* night-mode
     * setting, so a light-mode system drew black icons over this app's dark UI — the bug this call
     * site has always existed to defeat. The app's own resolved scheme is the only input.
     *
     * Re-invoking `enableEdgeToEdge` is the supported way to change the appearance after `onCreate`;
     * it is idempotent for the same style.
     */
    private fun applyBarStyle(dark: Boolean) {
        val style =
            if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /**
     * **Posted, not called inline**: a direct call puts the Play-services binder round trip and the
     * first `com.google.android.gms` class load on the critical path to the first frame. Still on the
     * main thread ([CastAvailability.initialize] is `@MainThread`), and `Dispatchers.Main` rather
     * than `Main.immediate`, which would run the body inline and defeat the posting.
     */
    private fun startCastStack() {
        lifecycleScope.launch(Dispatchers.Main) { castAvailability.initialize(this@MainActivity) }
    }

    /**
     * `setAutoEnterEnabled` (API 31+) lets the system capture the window as the user swipes home,
     * instead of the app entering picture-in-picture after the fact. Below 31, [onUserLeaveHint].
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
     * API 26–30 only; 31+ is already armed by [observePictureInPictureReadiness]. Guarded on the
     * player's state, or leaving from the library grid would float a still image.
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

    /** `setSeamlessResizeEnabled` (API 31+) tells the system this is video and can scale without a cross-fade. */
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
 * Asks once for `POST_NOTIFICATIONS` on API 33+. Declining is final and harmless: download work still
 * runs without it — the foreground promotion keeps it alive, not the notification being visible.
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

@Composable
internal fun JellyboostApp(sessionState: SessionState) {
    if (sessionState is SessionState.Unknown) return

    // Captured once: the start destination must not change under a live NavHost when the session
    // flips — sign-out navigates rather than rebuilding the graph.
    val startsSignedIn = remember { sessionState is SessionState.LoggedIn }

    AppScaffold(
        startsSignedIn = startsSignedIn,
        sessionState = sessionState,
    )
}
