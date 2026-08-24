package dev.jellyboost.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.player.R
import dev.jellyboost.player.gesture.GestureConfig
import dev.jellyboost.player.gesture.PlayerGestureController
import dev.jellyboost.player.gesture.SwipeTarget
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The invisible touch surface over the video. Must stay a sibling *below* the transport controls,
 * never a wrapper: a tap on a button is consumed by the button and everything else falls through.
 *
 * All gesture judgement belongs to [PlayerGestureController], where it is unit tested; only platform
 * plumbing lives here.
 *
 * @param swipesEnabled `false` while casting: both swipes act on this device's volume and backlight.
 *   The taps stay unconditional — they are how the controls come back.
 */
@Suppress(
    // The tap, double-tap and vertical-drag detectors must share one `pointerInput` scope to resolve
    // against each other; split across composables they each consume the same events.
    "LongMethod",
)
@Composable
internal fun PlayerGestureLayer(
    onToggleControls: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
    swipesEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = context as? Activity
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    val controller =
        remember(density) {
            PlayerGestureController(
                GestureConfig(
                    verticalExclusionPx = with(density) { VERTICAL_EXCLUSION.toPx() },
                    horizontalExclusionPx = with(density) { HORIZONTAL_EXCLUSION.toPx() },
                ),
            )
        }

    var indicator by remember { mutableStateOf<GestureIndicator?>(null) }
    // Keyed on the indicator so a continuing swipe restarts the linger rather than timing out mid-drag.
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(INDICATOR_LINGER_MS)
            indicator = null
        }
    }

    // Its own modifier rather than a branch inside the drag handler: an unoffered swipe must not be
    // *detected* either, or the pointer never reaches the system's own edge gestures.
    val swipes =
        if (!swipesEnabled) {
            Modifier
        } else {
            Modifier.pointerInput(controller, audioManager) {
                var target: SwipeTarget? = null
                var value = 0f

                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        target =
                            controller.swipeTargetFor(
                                xPx = offset.x,
                                yPx = offset.y,
                                widthPx = size.width.toFloat(),
                                heightPx = size.height.toFloat(),
                            )
                        value =
                            when (target) {
                                SwipeTarget.VOLUME -> audioManager.volumeFraction()
                                SwipeTarget.BRIGHTNESS -> activity.brightnessFraction()
                                null -> 0f
                            }
                    },
                    onDragEnd = { target = null },
                    onDragCancel = { target = null },
                    onVerticalDrag = { change, dragAmount ->
                        val current = target
                        if (current != null) {
                            change.consume()
                            value =
                                (value + controller.deltaFor(dragAmount, size.height.toFloat()))
                                    .coerceIn(0f, 1f)
                            when (current) {
                                SwipeTarget.VOLUME -> audioManager.setVolumeFraction(value)
                                SwipeTarget.BRIGHTNESS -> activity.setBrightnessFraction(value)
                            }
                            indicator = GestureIndicator(current, value)
                        }
                    },
                )
            }
        }

    // The tap surface must stay a real accessibility node, not only a `pointerInput`: touch
    // exploration consumes taps, so without an `onClick` action auto-hidden controls are unreachable.
    val revealLabel = stringResource(R.string.player_show_controls)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = revealLabel
                    onClick(label = revealLabel) {
                        onToggleControls()
                        true
                    }
                }
                // Only a request below API 29, so the controller ignores edge touches as well.
                .systemGestureExclusion()
                .pointerInput(controller) {
                    detectTapGestures(
                        onTap = { onToggleControls() },
                        onDoubleTap = { offset ->
                            controller.doubleTapSeekMs(offset.x, size.width.toFloat())?.let(onSeekBy)
                        },
                    )
                }.then(swipes),
    ) {
        indicator?.let { GestureIndicatorOverlay(it, Modifier.align(Alignment.Center)) }
    }
}

private data class GestureIndicator(
    val target: SwipeTarget,
    val value: Float,
)

/**
 * Labelled rather than semantics-cleared: this panel is the only feedback a swipe gives, so as a
 * polite live region it doubles as the announcement. Keep the bar and glyph merged into this node.
 */
@Composable
private fun GestureIndicatorOverlay(
    indicator: GestureIndicator,
    modifier: Modifier = Modifier,
) {
    val percent = (indicator.value * PERCENT).roundToInt()
    val spoken =
        when (indicator.target) {
            SwipeTarget.VOLUME -> stringResource(R.string.player_volume_percent, percent)
            SwipeTarget.BRIGHTNESS -> stringResource(R.string.player_brightness_percent, percent)
        }

    Row(
        modifier =
            modifier
                .semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                }.clip(RoundedCornerShape(Dimens.SpaceLarge))
                .background(Color.Black.copy(alpha = OVERLAY_ALPHA))
                .padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Icon(
            imageVector =
                when (indicator.target) {
                    SwipeTarget.VOLUME -> Icons.AutoMirrored.Outlined.VolumeUp
                    SwipeTarget.BRIGHTNESS -> Icons.Outlined.BrightnessMedium
                },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(Dimens.SpaceExtraLarge),
        )
        LinearProgressIndicator(
            progress = { indicator.value },
            modifier = Modifier.width(INDICATOR_BAR_WIDTH),
            color = Color.White,
            trackColor = Color.White.copy(alpha = TRACK_ALPHA),
        )
        Text(
            text = "$percent%",
            color = Color.White,
        )
    }
}

/**
 * `0f..1f`, or `0f` with no audio service. `internal` because the Display sheet must move the very
 * same volume and window brightness — a second implementation means a second set of rounding rules.
 */
internal fun AudioManager?.volumeFraction(): Float {
    val manager = this ?: return 0f
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return if (max <= 0) 0f else manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

internal fun AudioManager?.setVolumeFraction(fraction: Float) {
    val manager = this ?: return
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    manager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt().coerceIn(0, max), 0)
}

/**
 * `BRIGHTNESS_OVERRIDE_NONE` (-1) is a window's "follow the system" value, not a brightness: a swipe
 * starting from it would jump the screen to full dark, so the system setting seeds it instead.
 */
internal fun Activity?.brightnessFraction(): Float {
    val activity = this ?: return DEFAULT_BRIGHTNESS
    val attribute = activity.window.attributes.screenBrightness
    if (attribute in 0f..1f) return attribute
    val system =
        runCatching {
            Settings.System.getFloat(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            ) / SYSTEM_BRIGHTNESS_MAX
        }.getOrNull()
    return system?.coerceIn(0f, 1f) ?: DEFAULT_BRIGHTNESS
}

/**
 * A window attribute, never `Settings.System`: no permission, and the device setting is untouched.
 * The single-activity window outlives the player, so `ImmersiveLandscapeEffect` must restore it.
 */
internal fun Activity?.setBrightnessFraction(fraction: Float) {
    val window = this?.window ?: return
    window.attributes =
        WindowManager.LayoutParams().apply {
            copyFrom(window.attributes)
            screenBrightness = fraction.coerceIn(MIN_BRIGHTNESS, 1f)
        }
}

/** The system's back-gesture strip; a swipe starting here belongs to the system. */
private val HORIZONTAL_EXCLUSION = 48.dp

/** The status- and navigation-bar pull-down zones. */
private val VERTICAL_EXCLUSION = 64.dp

private val INDICATOR_BAR_WIDTH = 140.dp
private const val INDICATOR_LINGER_MS = 900L
private const val OVERLAY_ALPHA = 0.6f
private const val TRACK_ALPHA = 0.3f

/** Shared with the Display sheet so a swipe and a slider round identically. */
internal const val PERCENT = 100f

private const val DEFAULT_BRIGHTNESS = 0.5f
private const val SYSTEM_BRIGHTNESS_MAX = 255f

/** Never fully black: a brightness of zero looks exactly like a crash. */
private const val MIN_BRIGHTNESS = 0.01f
