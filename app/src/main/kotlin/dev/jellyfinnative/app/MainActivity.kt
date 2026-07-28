package dev.jellyfinnative.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.core.ui.theme.JellyfinTheme

/**
 * Single activity hosting the whole app.
 *
 * It holds the splash screen until session restore has answered, then hands the resulting
 * [SessionState] to [JellyfinNativeApp], which picks the start destination from it. The bottom
 * navigation bar and the offline banner join the NavHost in M2/M6.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { viewModel.sessionState.value is SessionState.Unknown }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            JellyfinTheme {
                NotificationPermissionRequest()
                val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
                JellyfinNativeApp(sessionState = sessionState, onSignOut = viewModel::signOut)
            }
        }
    }
}

/**
 * Asks once for `POST_NOTIFICATIONS` on API 33+.
 *
 * The download queue runs as foreground work and its notification is the only place a transfer can
 * be paused or cancelled from outside the app (docs/PLAN.md, "Download pipeline"). Without the
 * permission the work still runs — the promotion is what keeps it alive, not the notification being
 * *visible* — but the user loses that control surface, so it is worth one dialog.
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
internal fun JellyfinNativeApp(
    sessionState: SessionState,
    onSignOut: () -> Unit,
) {
    if (sessionState is SessionState.Unknown) return

    // Captured once: the start destination must not change under a live NavHost when the session
    // later flips — sign-out is handled by navigating, not by rebuilding the graph.
    val startsSignedIn = remember { sessionState is SessionState.LoggedIn }

    AppScaffold(
        startsSignedIn = startsSignedIn,
        sessionState = sessionState,
        onSignOut = onSignOut,
    )
}
