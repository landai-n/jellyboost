package dev.jellyboost.player.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.jellyboost.player.R

/** Declaration order is the bottom bar's drawing order. */
internal enum class SheetChipId {
    AUDIO,
    SUBTITLES,
    SPEED,
    GROUP,
    QUEUE,
    DISPLAY,
    QUALITY,
}

/**
 * `visible` rather than an already-filtered list, so [sheetChipSpecs] stays a *total* description of
 * the bar — `SheetChipSpecTest`'s worst-case sweep enumerates states, not chips.
 */
internal data class SheetChipSpec(
    val id: SheetChipId,
    val visible: Boolean,
)

/**
 * Must stay pure: [MAX_SHEET_CHIPS] and `LABELLED_BUTTONS_MIN_WIDTH` are derived from these rules by
 * a unit-test sweep. SyncPlay has no per-member rate, so speed is hidden in a group; display acts on
 * *this* device, so it is hidden while casting.
 */
internal fun sheetChipSpecs(state: PlayerUiState): List<SheetChipSpec> =
    listOf(
        SheetChipSpec(SheetChipId.AUDIO, state.audioTracks.size > 1),
        SheetChipSpec(SheetChipId.SUBTITLES, state.subtitleTracks.isNotEmpty()),
        SheetChipSpec(SheetChipId.SPEED, !state.syncPlay.inGroup && state.canSetSpeed),
        SheetChipSpec(SheetChipId.GROUP, state.syncPlay.inGroup),
        SheetChipSpec(SheetChipId.QUEUE, state.syncPlay.inGroup && state.syncPlay.hasQueue),
        SheetChipSpec(SheetChipId.DISPLAY, !state.cast.isCasting),
        SheetChipSpec(SheetChipId.QUALITY, !state.isLocalPlayback),
    )

internal fun visibleSheetChips(state: PlayerUiState): List<SheetChipSpec> = sheetChipSpecs(state).filter { it.visible }

/**
 * The most chips the bar can hold at once — what `PlayerControls.LABELLED_BUTTONS_MIN_WIDTH` is
 * measured against. `SheetChipSpecTest` re-derives it from [sheetChipSpecs] and fails if it moves.
 */
internal const val MAX_SHEET_CHIPS = 6

internal val SheetChipId.icon: ImageVector
    get() =
        when (this) {
            SheetChipId.AUDIO -> Icons.Outlined.MusicNote
            SheetChipId.SUBTITLES -> Icons.Outlined.ClosedCaption
            SheetChipId.SPEED -> Icons.Outlined.Speed
            SheetChipId.GROUP -> Icons.Outlined.Groups
            SheetChipId.QUEUE -> Icons.AutoMirrored.Outlined.PlaylistPlay
            SheetChipId.DISPLAY -> Icons.Outlined.Tune
            SheetChipId.QUALITY -> Icons.Outlined.HighQuality
        }

@Composable
internal fun SheetChipId.label(state: PlayerUiState): String =
    when (this) {
        SheetChipId.AUDIO -> stringResource(R.string.player_audio)
        SheetChipId.SUBTITLES -> stringResource(R.string.player_subtitles)
        SheetChipId.SPEED -> if (state.speed.isNormal) stringResource(R.string.player_speed) else state.speed.label
        SheetChipId.GROUP -> stringResource(R.string.player_syncplay_group)
        SheetChipId.QUEUE -> stringResource(R.string.player_syncplay_queue)
        SheetChipId.DISPLAY -> stringResource(R.string.player_display)
        SheetChipId.QUALITY -> stringResource(R.string.player_quality)
    }

/** Feeds the chip's `stateDescription`. */
internal fun SheetChipId.value(values: SheetChipValues): String? =
    when (this) {
        SheetChipId.AUDIO -> values.audio
        SheetChipId.SUBTITLES -> values.subtitles
        SheetChipId.SPEED -> values.speed
        SheetChipId.GROUP -> values.group
        SheetChipId.QUEUE -> values.queue
        // Two sliders rather than a selection: there is no single current value to speak.
        SheetChipId.DISPLAY -> null
        SheetChipId.QUALITY -> values.quality
    }

/**
 * `PlayerScreen` hosts the panel, never the control bar: a picker hosted by the bar is disposed
 * mid-selection when the bar auto-hides.
 */
internal val SheetChipId.panel: PlayerPanel
    get() =
        when (this) {
            SheetChipId.AUDIO -> PlayerPanel.AUDIO
            SheetChipId.SUBTITLES -> PlayerPanel.SUBTITLES
            SheetChipId.SPEED -> PlayerPanel.SPEED
            SheetChipId.QUALITY -> PlayerPanel.QUALITY
            SheetChipId.GROUP -> PlayerPanel.GROUP
            SheetChipId.QUEUE -> PlayerPanel.QUEUE
            SheetChipId.DISPLAY -> PlayerPanel.DISPLAY
        }

internal class SheetChipValues(
    val audio: String?,
    val subtitles: String,
    val speed: String,
    val quality: String,
    val group: String,
    val queue: String,
)

@Composable
internal fun sheetChipValues(state: PlayerUiState): SheetChipValues {
    val subtitlesOff = stringResource(R.string.player_subtitles_off)
    val quality = stringResource(state.quality.labelRes())
    val queue =
        pluralStringResource(
            R.plurals.player_syncplay_queue_count,
            state.syncPlay.queueSize,
            state.syncPlay.queueSize,
        )

    return SheetChipValues(
        audio = state.audioTracks.firstOrNull { it.index == state.selectedAudioIndex }?.label,
        subtitles =
            state.subtitleTracks.firstOrNull { it.index == state.selectedSubtitleIndex }?.label
                ?: subtitlesOff,
        speed = state.speed.label,
        quality = quality,
        group = state.syncPlay.groupName,
        queue = queue,
    )
}
