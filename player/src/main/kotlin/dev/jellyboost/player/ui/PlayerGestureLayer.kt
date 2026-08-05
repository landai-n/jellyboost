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
 * The invisible touch surface over the video (docs/PLAN.md, "M9 Polish" → gestures).
 *
 * It is a sibling *below* the transport controls rather than a wrapper around them: a tap that
 * lands on a button is consumed by the button, and everything else falls through to here. That is
 * what lets a single tap toggle the controls without every icon needing to know about gestures.
 *
 * The judgement — which half of the screen, which third, how far a swipe has to travel, which edges
 * belong to the system — is [PlayerGestureController]'s and is unit tested. What is left here is
 * the platform plumbing that cannot be: `AudioManager` for volume, the window's `screenBrightness`
 * attribute for brightness (a per-window override restored by `PlayerScreen`'s immersive effect on
 * the way out, so it never touches the device setting), and a transient indicator.
 *
 * @param swipesEnabled whether the vertical swipes are offered at all. `false` while casting: both
 *   of them act on *this* device — its media volume, its backlight — and neither means anything
 *   while the film is being decoded in a television, whose volume rides the hardware keys through
 *   the Cast framework. The taps are unconditional, because they are how the controls come back.
 */
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
    // Reset by every new indicator, so a continuing swipe keeps it on screen and a finished one
    // lets it fade out on its own.
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(INDICATOR_LINGER_MS)
            indicator = null
        }
    }

    // Built as its own modifier rather than branched inside the drag handler: a swipe that is not
    // offered should not be *detected* either, so that the pointer never leaves the parent — which
    // is what lets the system's own edge gestures work normally while casting.
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

    // The tap surface has to be a real accessibility node, not only a `pointerInput` (accessibility
    // audit 2026-08-05, CR-1): touch exploration consumes taps, so without an `onClick` action a
    // TalkBack user whose controls had auto-hidden could never bring them back — the film would
    // play on with no reachable transport at all. The raw gesture detector stays for touch; this
    // adds the node TalkBack, Switch Access and every other action-driven service need.
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
                // Asks the system not to steal touches that start near the edges; the controller
                // additionally ignores them, because this is only a request below API 29.
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

/** What the transient overlay is showing. */
private data class GestureIndicator(
    val target: SwipeTarget,
    val value: Float,
)

/**
 * The transient "volume 60%" / "brightness 40%" panel a swipe raises.
 *
 * Labelled rather than semantics-cleared (accessibility audit 2026-08-05, A11Y-P-16): the panel *is*
 * the feedback for a gesture that otherwise changes nothing on screen, so as a polite live region it
 * doubles as the announcement of what the swipe just did. The bar and the glyph inside it stay
 * unlabelled and are merged into this one node — three announcements for one gesture would be worse
 * than none.
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
 * Current media volume as `0f..1f`, or `0f` when there is no audio service to ask.
 *
 * `internal` since the accessibility pass: the Display sheet (`PlayerSheets`) is the non-gesture way
 * to the same two levels (audit CR-8), and it has to move *the same* volume and *the same* window
 * brightness the swipes do — a second implementation would be a second set of rounding rules and a
 * second place for the brightness override to leak out of.
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
 * The window's brightness override, or the device's own brightness the first time.
 *
 * `BRIGHTNESS_OVERRIDE_NONE` (-1) is the "follow the system" value a window starts with, and it is
 * not a brightness — starting a swipe from it would jump the screen to full dark. The system
 * setting is read once to seed the gesture instead.
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
 * Sets brightness for this window only.
 *
 * A window attribute rather than `Settings.System`: it needs no permission and never touches the
 * device setting. The app is single-activity, so the window itself *outlives* the player —
 * `ImmersiveLandscapeEffect` in `PlayerScreen` captures the previous override and restores it when
 * the player leaves (audit PC-02); a film watched dimmed must not leave the whole app dark.
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

/** Shared with the Display sheet, so a swipe and a slider report the same level the same way. */
internal const val PERCENT = 100f

private const val DEFAULT_BRIGHTNESS = 0.5f
private const val SYSTEM_BRIGHTNESS_MAX = 255f

/** Never fully black: a brightness of zero looks exactly like a crash. */
private const val MIN_BRIGHTNESS = 0.01f
