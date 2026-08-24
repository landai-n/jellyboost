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
 * The cast button, for any app bar that wants one.
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
 * Beyond that, [CastDeviceState.NoDevices] *hides* the view rather than composing it out, and the
 * distinction is load-bearing on both sides:
 * - The view must stay attached, because an attached `MediaRouteButton` is what registers the
 *   `MediaRouter` callback that requests route discovery. Composing it out on [NoDevices] was a
 *   chicken-and-egg: no button → no discovery → the state never left NoDevices → no button.
 * - It must be hidden *by us*, because the raw `MediaRouteButton` does not hide itself with no
 *   routes around — auto-hiding is `MediaRouteActionProvider` behaviour, and the bare view just
 *   sits there dimmed (seen on the tablet walk: a lone oversized glyph beside the circled actions).
 *
 * `View.INVISIBLE` (not `GONE`) keeps the attached view laid out, and takes it out of hit-testing
 * so an invisible corner of the bar cannot open the route chooser. The glass circle is gated on the
 * same state, so glyph and circle agree in every state: an empty (but reserved) slot without
 * receivers, circle + glyph as soon as one is discovered.
 *
 * @param glassContainer draws the 2026 refresh's glass circle behind the button, for the bars whose
 *   other actions are `GlassIconButton`s.
 * @param size diameter of that circle, matching the `GlassIconButton`s beside it —
 *   [Dimens.PillHeightSmall] in chrome, [Dimens.PillHeight] in the player's top bar. The caller's
 *   [modifier] carries the *frame* around it (a `GlassIconButton` reserves
 *   [Dimens.MinTouchTarget]); this is the drawn surface, so the two lines of buttons agree.
 * @param surfaceTint the circle's glass fill, as on `GlassIconButton`.
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
    // "Cast to a device" is a lie once a device has it: the button's *state* is the
    // one thing a user who cannot see the filled glyph has no other way to learn, and it is also the
    // answer to "why is nothing playing here". The connected name reuses the same sentence the
    // casting backdrop draws, so the screen and the button say the same thing.
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
 * The AppCompat view, and the only place in the app's UI that names a Cast type.
 *
 * The [ContextThemeWrapper] is not optional: `MediaRouteButton` resolves AppCompat and MediaRouter
 * attributes from its context, and this app's window theme is a bare frame around Compose. The
 * chooser dialog it opens is themed elsewhere — see `player/src/main/res/values/themes.xml`.
 *
 * `CastButtonFactory` gives the button the route selector that matches the configured receiver and
 * keeps its connection state in step with the session. It needs an initialised `CastContext`, which
 * the caller's [CastDeviceState] guard already guarantees.
 *
 * @param visible `View.VISIBLE` vs `View.INVISIBLE` — never `GONE`, and never conditional
 *   composition: the attached view is what keeps route discovery running (see [CastRouteButton]).
 * @param description what the button says it does *right now*. Applied in `update` rather than in
 *   `factory` because it changes with the session, and a description set once would go on claiming
 *   the film is here long after it left.
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

/**
 * What a *bare* cast button (no glass circle) occupies — Material's 48dp minimum touch target, with
 * the view drawing its own 24dp icon centred in it. The glass variant sizes itself from its caller
 * instead, so that its circle matches the `GlassIconButton`s it sits beside.
 */
private val CastButtonSize: Dp = Dimens.MinTouchTarget

/**
 * Holds the cast state for [CastRouteButton].
 *
 * A view over the `@Singleton` [CastAvailability], shaped exactly like `:app`'s
 * `SyncPlayBadgeViewModel` over `SyncPlaySession`: the button is used from several bars in
 * different ViewModel-store owners, and each of them getting the same singleton's flow is precisely
 * what is wanted.
 */
@HiltViewModel
internal class CastRouteButtonViewModel
    @Inject
    constructor(
        castAvailability: CastAvailability,
    ) : ViewModel() {
        val state: StateFlow<CastDeviceState> = castAvailability.state
    }
