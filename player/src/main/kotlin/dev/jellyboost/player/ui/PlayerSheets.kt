package dev.jellyboost.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
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
import dev.jellyboost.core.ui.component.JellyboostAlertDialog
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.player.R
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.model.PlaybackTrack
import kotlin.math.roundToInt
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * Dialogs, not bottom sheets: the player runs in landscape, where a sheet covers the seek bar the user came from.
 *
 * All of them are hosted by `PlayerScreen`'s `PanelHost`, never by the control bar, which disposes itself four
 * seconds after it appears and would take an open picker with it.
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

/** "Off" is the first row: a real choice, not an absence. */
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

/** Never offered while the bytes come off the disk (`sheetChipSpecs`). */
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

/** Never offered in a SyncPlay group, which has no per-member rate. */
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
 * The accessible path to brightness and volume, which are otherwise only a vertical drag — unreachable with a
 * screen reader, a switch device or a keyboard. The sliders drive the same plumbing as the swipes (the window's
 * `screenBrightness` override and the media stream volume), so the two cannot disagree.
 */
@Composable
internal fun PlayerDisplayDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    // Write-through on both sides, so state and device stay in step without a poll.
    var brightness by remember { mutableFloatStateOf(activity.brightnessFraction()) }
    var volume by remember { mutableFloatStateOf(audioManager.volumeFraction()) }

    JellyboostAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.player_done)) }
        },
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
    )
}

/**
 * One labelled level, `0f..1f`. Plain M3 `Slider` on purpose — its range semantics and `setProgress` action are
 * what a screen reader, a keyboard and Switch Access need; only the label and the spoken percentage are added.
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

/** [key] is `null` for the "off" entry. */
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
    JellyboostAlertDialog(
        onDismissRequest = onDismiss,
        // The app's own "Cancel", never `android.R.string.cancel`: the platform string follows the *device*
        // locale and would show "Annuler" beside this app's English rows.
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(CoreUiR.string.action_cancel)) }
        },
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
                                    // The row *is* the radio button: the control inside it is inert
                                    // (`onClick = null`), so without the role the picker announces as bare taps.
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
    )
}

private fun PlaybackTrack.asOption(selected: Boolean): Option =
    Option(key = index, label = label.ifBlank { language.orEmpty() }, selected = selected)

/** Shared with the bottom bar's quality chip. */
internal fun PlaybackQuality.labelRes(): Int =
    when (this) {
        PlaybackQuality.AUTO -> R.string.player_quality_auto
        PlaybackQuality.HIGH -> R.string.player_quality_high
        PlaybackQuality.MEDIUM -> R.string.player_quality_medium
        PlaybackQuality.LOW -> R.string.player_quality_low
        PlaybackQuality.LOWEST -> R.string.player_quality_lowest
    }
