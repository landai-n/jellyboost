package dev.jellyboost.player.ui

import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.player.R
import dev.jellyboost.player.cast.CastAvailability
import dev.jellyboost.player.cast.CastDeviceState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * **Nothing is drawn, and no Play services class is touched, while [CastDeviceState.Unavailable]
 * holds** — the guard that lets one APK ship to devices without them. Every GMS type sits in
 * [MediaRouteButtonHost], which such a device never calls.
 *
 * [CastDeviceState.NoDevices] must *hide* the view, never compose it out: an attached
 * `MediaRouteButton` is what registers the `MediaRouter` callback that requests route discovery, so
 * composing it out is a chicken-and-egg — no button, no discovery, no button. It also has to be
 * hidden by us: the bare view does not auto-hide (that is `MediaRouteActionProvider` behaviour).
 * `INVISIBLE` not `GONE`, so the view stays laid out but out of hit-testing.
 *
 * @param size diameter of the glass circle; [modifier] carries the frame around it.
 */
@Composable
fun CastRouteButton(
    modifier: Modifier = Modifier,
    glassContainer: Boolean = false,
    size: Dp = Dimens.PillHeightSmall,
    surfaceTint: Color = GlassDefaults.Fill,
) {
    val viewModel: CastRouteButtonViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state == CastDeviceState.Unavailable) return
    val hasReceivers = state != CastDeviceState.NoDevices
    // The connected name reuses the sentence the casting backdrop draws, so both say the same thing.
    val connected = state as? CastDeviceState.Connected
    val description =
        if (connected == null) {
            stringResource(R.string.player_cast)
        } else {
            stringResource(
                R.string.player_casting_to,
                connected.deviceName ?: stringResource(R.string.player_cast_device_unnamed),
            )
        }

    if (!glassContainer) {
        MediaRouteButtonHost(
            visible = hasReceivers,
            description = description,
            modifier = modifier.size(CastButtonSize),
        )
        return
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .then(
                        if (hasReceivers) {
                            Modifier.glassSurface(shape = CircleShape, tint = surfaceTint)
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            MediaRouteButtonHost(
                visible = hasReceivers,
                description = description,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * The only place in the app's UI that names a Cast type.
 *
 * The [ContextThemeWrapper] is not optional: `MediaRouteButton` resolves AppCompat and MediaRouter
 * attributes from its context, and this app's window theme is a bare frame around Compose.
 * `CastButtonFactory` requires an initialised `CastContext`, which the caller's guard guarantees.
 *
 * @param visible `VISIBLE`/`INVISIBLE` only — never `GONE`, never conditional composition.
 * @param description set in `update`, not `factory`: it changes with the session.
 */
@Composable
private fun MediaRouteButtonHost(
    visible: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val themed = ContextThemeWrapper(context, R.style.Theme_Jellyboost_Cast)
            MediaRouteButton(themed).apply {
                CastButtonFactory.setUpMediaRouteButton(themed, this)
            }
        },
        update = { view ->
            view.contentDescription = description
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
    )
}

/** A bare cast button (no glass circle): Material's 48dp minimum touch target. */
private val CastButtonSize: Dp = Dimens.MinTouchTarget

/**
 * A view over the `@Singleton` [CastAvailability]: the button appears in several bars with different
 * ViewModel-store owners, and every one of them must observe the same singleton's flow.
 */
@HiltViewModel
internal class CastRouteButtonViewModel
    @Inject
    constructor(
        castAvailability: CastAvailability,
    ) : ViewModel() {
        val state: StateFlow<CastDeviceState> = castAvailability.state
    }
