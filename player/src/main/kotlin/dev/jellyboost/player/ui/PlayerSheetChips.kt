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

/**
 * Which picker a bottom-bar chip is; the row's drawing order is this enum's own.
 *
 * ### What this file is
 * Which pickers the player's bottom bar offers, and what each of them says.
 *
 * All of it used to live inline in `PlayerControls.BottomBar`: seven `if`s over six different
 * [PlayerUiState] fields, interleaved with the chips they guarded (audit CPX-7). The row could not
 * be reasoned about without reading the layout, and the one number the bar's width threshold is
 * measured against — how many pickers can be up at once — had no answer short of putting a tablet in
 * a group and counting. Split out, the rules are a pure function over the state ([sheetChipSpecs]),
 * the drawing is a `forEach`, and the count is arithmetic a unit test performs ([MAX_SHEET_CHIPS]).
 *
 * The split is by *question*, not by size: this file answers "which chips, saying what", and
 * `PlayerControls` answers "what a chip looks like and where the row sits".
 */
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
 * One picker in the bottom bar, and whether the state currently offers it.
 *
 * `visible` rather than an already-filtered list, so [sheetChipSpecs] is a *total* description of the
 * bar — every chip the row can draw, present in every state, with one boolean saying why it is or is
 * not there. That is what makes `SheetChipSpecTest`'s worst-case sweep possible: it enumerates the
 * states, not the chips.
 */
internal data class SheetChipSpec(
    val id: SheetChipId,
    val visible: Boolean,
)

/**
 * Which pickers the bottom bar offers, given the state — the seven rules, in one place.
 *
 * Pure and `internal` so the rules are a unit test rather than a screenshot, and so the question
 * behind `LABELLED_BUTTONS_MIN_WIDTH` becomes arithmetic — see [MAX_SHEET_CHIPS].
 *
 * Each rule, unchanged from the inline version and worth restating because none of them is arbitrary:
 * - **audio** when there is more than one track — a picker with one row picks nothing;
 * - **subtitles** whenever the item has any, because "off" is always one of the choices;
 * - **speed** only outside a group and only where the deciding player has a rate at all. SyncPlay has
 *   no per-member rate (playing faster than the group is drifting from it,
 *   docs/notes/syncplay-m11-plan.md key decision 11), and a receiver publishes whether it takes one
 *   ([PlayerUiState.canSetSpeed]);
 * - **group** while in one — the participants, the shuffle/repeat and the way out (M11 Phase 3);
 * - **queue** while in a group *that has a queue*: before the first `PlayQueueUpdate` the sheet would
 *   have nothing in it (M11 Phase 4);
 * - **display** unless a television has the film: brightness and volume act on *this* device, which
 *   is exactly the condition the swipes they replace are offered under (audit CR-8);
 * - **quality** unless the bytes are coming off the disk — a downloaded file has no streaming bitrate
 *   to cap, so the picker would be inert.
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

/** The chips actually drawn, in row order. */
internal fun visibleSheetChips(state: PlayerUiState): List<SheetChipSpec> = sheetChipSpecs(state).filter { it.visible }

/**
 * The most pickers the bar can hold at once — the number `PlayerControls.LABELLED_BUTTONS_MIN_WIDTH`
 * is measured against, and the one thing about the row a width sweep cannot be re-run without.
 *
 * Not a comment any more: `SheetChipSpecTest` derives it from [sheetChipSpecs] by sweeping every
 * combination of the seven state inputs the rules read, and fails if the answer moves. Adding an
 * eighth picker, or loosening a rule so two that used to exclude each other can now both appear,
 * therefore breaks a test rather than silently overflowing a bar somebody measured in 2026.
 *
 * The worst case is **in a group with a queue**: audio, subtitles, group, queue, display and quality,
 * with speed composed out (there is no per-member rate in SyncPlay). Solo it is five — audio,
 * subtitles, speed, display, quality — which is the case the device sweep behind that threshold
 * actually measured, and the number the bottom bar's comments claimed was the worst case until the
 * accessibility audit's display picker (CR-8) made it six. See `showSheetButtonLabels` for what that
 * means for the threshold.
 */
internal const val MAX_SHEET_CHIPS = 6

/** The chip's glyph. */
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

/** The chip's words — one string resource each, except the rate, which says itself. */
@Composable
internal fun SheetChipId.label(state: PlayerUiState): String =
    when (this) {
        SheetChipId.AUDIO -> stringResource(R.string.player_audio)
        SheetChipId.SUBTITLES -> stringResource(R.string.player_subtitles)
        // The current rate replaces the word once it is not 1×, so the control says what it is doing
        // without needing a second badge next to it.
        SheetChipId.SPEED -> if (state.speed.isNormal) stringResource(R.string.player_speed) else state.speed.label
        SheetChipId.GROUP -> stringResource(R.string.player_syncplay_group)
        SheetChipId.QUEUE -> stringResource(R.string.player_syncplay_queue)
        SheetChipId.DISPLAY -> stringResource(R.string.player_display)
        SheetChipId.QUALITY -> stringResource(R.string.player_quality)
    }

/** What this picker is currently set to, for the chip's `stateDescription` — see [SheetChipValues]. */
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
 * What a tap on this chip opens.
 *
 * Two destinations, and which is which is not cosmetic: the four sheets the control bar hosts itself
 * go through [onOpenSheet], while the three panels `PlayerScreen` hosts — display, group, queue — go
 * through [PlayerActions], because they have to survive the bar composing itself out four seconds
 * later (see [PlayerActions.onOpenDisplaySheet] and [PlayerPanel]).
 */
internal fun SheetChipId.open(
    actions: PlayerActions,
    onOpenSheet: (PlayerSheet) -> Unit,
) = when (this) {
    SheetChipId.AUDIO -> onOpenSheet(PlayerSheet.AUDIO)
    SheetChipId.SUBTITLES -> onOpenSheet(PlayerSheet.SUBTITLES)
    SheetChipId.SPEED -> onOpenSheet(PlayerSheet.SPEED)
    SheetChipId.QUALITY -> onOpenSheet(PlayerSheet.QUALITY)
    SheetChipId.GROUP -> actions.onOpenGroupSheet()
    SheetChipId.QUEUE -> actions.onOpenQueueSheet()
    SheetChipId.DISPLAY -> actions.onOpenDisplaySheet()
}

/**
 * What each picker is currently set to (audit A11Y-P-09).
 *
 * The chips announce "Audio", "Subtitles", "Quality" — which track, which language, which cap? The
 * answer becomes each chip's `stateDescription`, so a picker says what it is doing without anyone
 * having to open it, exactly as the settings rows do.
 *
 * Assembled unconditionally, in one place, because every field is cheap and the alternative is six
 * `firstOrNull { … } ?: …` scattered through the row. Which of them a chip reads is
 * [SheetChipId.value]'s table; whether that chip is drawn at all is [sheetChipSpecs]'.
 */
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
