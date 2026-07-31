package dev.jellyboost.player.ui

import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.player.R
import dev.jellyboost.player.cast.CastAvailability
import dev.jellyboost.player.cast.CastDeviceState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The cast button, for any app bar that wants one (docs/notes/chromecast-m12-plan.md, decision 9).
 *
 * Deliberately self-contained — it sources its own state — so that adding casting to a bar is one
 * line and no screen has to learn what a `CastContext` is. The player's own top bar picks it up the
 * same way in a later phase.
 *
 * **Nothing is drawn, and no Google Play services class is touched, while
 * [CastDeviceState.Unavailable] holds.** That is the contract that lets one APK ship to devices
 * with no Play services at all: the guard sits in this function, and everything that names a GMS
 * type sits in [MediaRouteButtonHost], which such a device never calls.
 *
 * Beyond that the button decides its own visibility: MediaRouter hides it whenever there is nothing
 * to route to, which is why [CastDeviceState.NoDevices] is not filtered here — letting the view
 * animate itself in as receivers appear is smoother than composing it in and out.
 */
@Composable
fun CastRouteButton(modifier: Modifier = Modifier) {
    val viewModel: CastRouteButtonViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state == CastDeviceState.Unavailable) return

    MediaRouteButtonHost(modifier = modifier)
}

/**
 * The AppCompat view, and the only place in the app's UI that names a Cast type.
 *
 * The [ContextThemeWrapper] is not optional: `MediaRouteButton` resolves AppCompat and MediaRouter
 * attributes from its context, and this app's window theme is a bare frame around Compose. The
 * chooser dialog it opens is themed elsewhere — see `player/src/main/res/values/themes.xml`.
 *
 * `CastButtonFactory` gives the button the route selector that matches the configured receiver and
 * keeps its connection state in step with the session. It needs an initialised `CastContext`, which
 * the caller's [CastDeviceState] guard already guarantees.
 */
@Composable
private fun MediaRouteButtonHost(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.size(CastButtonSize),
        factory = { context ->
            val themed = ContextThemeWrapper(context, R.style.Theme_Jellyboost_Cast)
            MediaRouteButton(themed).apply {
                contentDescription = context.getString(R.string.player_cast)
                CastButtonFactory.setUpMediaRouteButton(themed, this)
            }
        },
    )
}

/**
 * An M3 `IconButton`'s size — the view draws its own 24dp icon centred in it, so the cast button
 * lines up with the app-bar actions beside it and keeps the same 48dp touch target.
 */
private val CastButtonSize: Dp = 48.dp

/**
 * Holds the cast state for [CastRouteButton].
 *
 * A view over the `@Singleton` [CastAvailability], shaped exactly like `:app`'s
 * `SyncPlayBadgeViewModel` over `SyncPlaySession`: the button is used from several bars in
 * different ViewModel-store owners, and each of them getting the same singleton's flow is precisely
 * what is wanted.
 */
@HiltViewModel
class CastRouteButtonViewModel
    @Inject
    constructor(
        castAvailability: CastAvailability,
    ) : ViewModel() {
        val state: StateFlow<CastDeviceState> = castAvailability.state
    }
