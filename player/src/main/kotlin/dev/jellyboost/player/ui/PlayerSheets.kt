package dev.jellyboost.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.player.R
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.model.PlaybackTrack
import kotlin.math.roundToInt

/**
 * The four track/quality/rate pickers, one composable each.
 *
 * A dialog rather than a bottom sheet: the player runs in landscape, where a sheet rising from the
 * bottom edge covers the seek bar the user just came from, and dialogs need no experimental API.
 *
 * They used to be branches of a `PlayerSheetHost` the *control bar* called, which is what audit UI-1
 * was about — the bar disposes itself four seconds after it appears and took the open picker with
 * it. The `when` that chooses between them is now `PlayerScreen`'s `PanelHost`, one exhaustive
 * branch per [PlayerPanel], so every panel on this screen is hosted in the same place and outlives
 * the bar. Nothing about the dialogs themselves changed.
 */
@Composable
internal fun PlayerAudioDialog(
    state: PlayerUiState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionDialog(
        title = stringResource(R.string.player_audio),
        options = state.audioTracks.map { it.asOption(selected = it.index == state.selectedAudioIndex) },
        onSelect = { index -> index?.let(onSelect) },
        onDismiss = onDismiss,
    )
}

/** The subtitle picker — "off" is the first row, because it is a real choice, not an absence. */
@Composable
internal fun PlayerSubtitleDialog(
    state: PlayerUiState,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionDialog(
        title = stringResource(R.string.player_subtitles),
        options =
            listOf(
                Option(
                    key = null,
                    label = stringResource(R.string.player_subtitles_off),
                    selected = state.selectedSubtitleIndex == null,
                ),
            ) + state.subtitleTracks.map { it.asOption(selected = it.index == state.selectedSubtitleIndex) },
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** The streaming bitrate cap; never offered while the bytes come off the disk (`sheetChipSpecs`). */
@Composable
internal fun PlayerQualityDialog(
    state: PlayerUiState,
    onSelect: (PlaybackQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionDialog(
        title = stringResource(R.string.player_quality),
        options =
            PlaybackQuality.entries.map { quality ->
                Option(
                    key = quality.ordinal,
                    label = stringResource(quality.labelRes()),
                    selected = quality == state.quality,
                )
            },
        onSelect = { ordinal -> ordinal?.let { onSelect(PlaybackQuality.entries[it]) } },
        onDismiss = onDismiss,
    )
}

/** The playback rate; never offered in a SyncPlay group, which has no per-member rate. */
@Composable
internal fun PlayerSpeedDialog(
    state: PlayerUiState,
    onSelect: (PlaybackSpeed) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionDialog(
        title = stringResource(R.string.player_speed),
        options =
            PlaybackSpeed.entries.map { speed ->
                Option(key = speed.ordinal, label = speed.label, selected = speed == state.speed)
            },
        onSelect = { ordinal -> ordinal?.let { onSelect(PlaybackSpeed.entries[it]) } },
        onDismiss = onDismiss,
    )
}

/**
 * Brightness and volume, as controls rather than as gestures (accessibility audit 2026-08-05, CR-8).
 *
 * Both were reachable only by a precise vertical drag on the correct third of the video: unusable
 * with a screen reader (touch exploration consumes the drag), with a switch device, with a keyboard,
 * or by anyone whose motor control does not include a measured 200 px swipe. Brightness had no
 * fallback of any kind — unlike the double-tap seek, which at least has buttons.
 *
 * This *adds* a path; the swipes are untouched (docs/PLAN.md M9 lists gestures as a feature, and
 * nothing here removes one). Both sliders drive exactly the plumbing the swipes drive — the window's
 * `screenBrightness` override, restored by `ImmersiveLandscapeEffect` on the way out, and the media
 * stream's volume — so the two ways of asking cannot disagree, and neither touches a device setting.
 *
 * The sheet is hosted by `PlayerScreen`, not by the control bar, so the four-second auto-hide cannot
 * take it away mid-adjustment.
 */
@Composable
internal fun PlayerDisplayDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    // Seeded from what is in force right now, then owned by the sliders: the window override and the
    // stream volume are both write-through, so the state and the device stay in step without a poll.
    var brightness by remember { mutableFloatStateOf(activity.brightnessFraction()) }
    var volume by remember { mutableFloatStateOf(audioManager.volumeFraction()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier.border(
                width = GlassDefaults.HairlineWidth,
                color = GlassDefaults.PanelHairline,
                shape = MaterialTheme.shapes.extraLarge,
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = stringResource(R.string.player_display), style = JellyfinTypeExtras.SectionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
                LevelSlider(
                    label = stringResource(R.string.player_brightness),
                    value = brightness,
                    onValueChange = { value ->
                        brightness = value
                        activity.setBrightnessFraction(value)
                    },
                )
                LevelSlider(
                    label = stringResource(R.string.player_volume),
                    value = volume,
                    onValueChange = { value ->
                        volume = value
                        audioManager.setVolumeFraction(value)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.player_done)) }
        },
    )
}

/**
 * One labelled level, `0f..1f`.
 *
 * A plain M3 `Slider` — its range semantics and its `setProgress` action are exactly what a screen
 * reader, a keyboard and Switch Access all need — with the two things it cannot know: what it
 * controls ([label]) and what "0.7" means out loud. The percentage is a `stateDescription` rather
 * than a visible readout because the slider's own fill already shows it to anyone who can see it.
 */
@Composable
private fun LevelSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val percent = stringResource(R.string.player_percent, (value * PERCENT).roundToInt())

    Column {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier.semantics {
                    contentDescription = label
                    stateDescription = percent
                },
        )
    }
}

/** One row in a picker. [key] is `null` for the "off" entry, which is a real choice, not an absence. */
private data class Option(
    val key: Int?,
    val label: String,
    val selected: Boolean,
)

@Composable
private fun OptionDialog(
    title: String,
    options: List<Option>,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier.border(
                width = GlassDefaults.HairlineWidth,
                color = GlassDefaults.PanelHairline,
                shape = MaterialTheme.shapes.extraLarge,
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = title, style = JellyfinTypeExtras.SectionTitle) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option.selected,
                                    // The row *is* the radio button — the control inside it is inert
                                    // (`onClick = null`), so without the role the whole picker
                                    // announces as unlabelled taps rather than as a choice among
                                    // choices (audit A11Y-P-12).
                                    role = Role.RadioButton,
                                    onClick = {
                                        onSelect(option.key)
                                        onDismiss()
                                    },
                                ).padding(vertical = Dimens.SpaceSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.selected, onClick = null)
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = Dimens.SpaceMedium),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
        },
    )
}

private fun PlaybackTrack.asOption(selected: Boolean): Option =
    Option(key = index, label = label.ifBlank { language.orEmpty() }, selected = selected)

/** Shared with the bottom bar's quality chip, which speaks the current cap as its state. */
internal fun PlaybackQuality.labelRes(): Int =
    when (this) {
        PlaybackQuality.AUTO -> R.string.player_quality_auto
        PlaybackQuality.HIGH -> R.string.player_quality_high
        PlaybackQuality.MEDIUM -> R.string.player_quality_medium
        PlaybackQuality.LOW -> R.string.player_quality_low
        PlaybackQuality.LOWEST -> R.string.player_quality_lowest
    }
