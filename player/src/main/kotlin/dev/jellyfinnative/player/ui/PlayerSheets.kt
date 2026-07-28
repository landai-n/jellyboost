package dev.jellyfinnative.player.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.player.R
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackTrack

/**
 * Renders whichever picker is open.
 *
 * A dialog rather than a bottom sheet: the player runs in landscape, where a sheet rising from the
 * bottom edge covers the seek bar the user just came from, and dialogs need no experimental API.
 */
@Composable
internal fun PlayerSheetHost(
    sheet: PlayerSheet?,
    state: PlayerUiState,
    actions: PlayerActions,
    onDismiss: () -> Unit,
) {
    when (sheet) {
        null -> Unit

        PlayerSheet.AUDIO ->
            OptionDialog(
                title = stringResource(R.string.player_audio),
                options = state.audioTracks.map { it.asOption(selected = it.index == state.selectedAudioIndex) },
                onSelect = { index -> index?.let(actions.onSelectAudio) },
                onDismiss = onDismiss,
            )

        PlayerSheet.SUBTITLES ->
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
                onSelect = actions.onSelectSubtitle,
                onDismiss = onDismiss,
            )

        PlayerSheet.QUALITY ->
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
                onSelect = { ordinal ->
                    ordinal?.let { actions.onSelectQuality(PlaybackQuality.entries[it]) }
                },
                onDismiss = onDismiss,
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
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option.selected,
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

private fun PlaybackQuality.labelRes(): Int =
    when (this) {
        PlaybackQuality.AUTO -> R.string.player_quality_auto
        PlaybackQuality.HIGH -> R.string.player_quality_high
        PlaybackQuality.MEDIUM -> R.string.player_quality_medium
        PlaybackQuality.LOW -> R.string.player_quality_low
        PlaybackQuality.LOWEST -> R.string.player_quality_lowest
    }
