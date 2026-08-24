package dev.jellyboost.player.ui

import androidx.compose.ui.unit.dp
import dev.jellyboost.player.model.PlaybackTrack
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [showSheetButtonLabels] — the one decision behind the bottom bar's labelled and
 * icon-only pickers, kept out of the composable so the threshold can be checked without a device.
 */
class PlayerControlsTest {
    @Test
    fun `a phone in landscape gets icon-only pickers`() {
        // The sweep's worst case: five pickers and the clock had near-zero slack at this width.
        showSheetButtonLabels(800.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `a tablet in portrait gets icon-only pickers`() {
        showSheetButtonLabels(711.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `the threshold itself is labelled`() {
        showSheetButtonLabels(840.dp, fontScale = 1f) shouldBe true
    }

    @Test
    fun `the capped tablet-landscape bar keeps its labels`() {
        // The bar is capped at 1000dp, so this is what the tablet renders — it must not change.
        showSheetButtonLabels(1000.dp, fontScale = 1f) shouldBe true
    }

    @Test
    fun `a hand-held width well under the threshold is icon-only`() {
        showSheetButtonLabels(640.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `the tablet bar drops its labels once the text is scaled up`() {
        // Same 1000dp bar, 1.5x text: the words are half again as wide, so the row that fitted them
        // at 12sp no longer does and the pickers go icon-only rather than clipping.
        showSheetButtonLabels(1000.dp, fontScale = 1.5f) shouldBe false
    }

    @Test
    fun `a wide enough bar keeps its labels even at a scaled-up text size`() {
        // 840 * 1.5 = 1260: the threshold moves with the text rather than switching everything off.
        showSheetButtonLabels(1260.dp, fontScale = 1.5f) shouldBe true
        showSheetButtonLabels(1259.dp, fontScale = 1.5f) shouldBe false
    }

    @Test
    fun `the largest accessibility text size takes every bar to icon-only`() {
        // 2x needs 1680dp, which no viewport this app runs on has — deliberately.
        showSheetButtonLabels(1000.dp, fontScale = 2f) shouldBe false
    }

    @Test
    fun `smaller text does not lower the threshold`() {
        // The sweep's number is a floor: a row judged too tight for these words stays icon-only.
        showSheetButtonLabels(800.dp, fontScale = 0.85f) shouldBe false
        showSheetButtonLabels(840.dp, fontScale = 0.85f) shouldBe true
    }
}

/**
 * The rules live in [sheetChipSpecs] rather than as `if`s inline in `BottomBar`, so
 * [MAX_SHEET_CHIPS] is derived here from a sweep of all 128 combinations of the seven inputs,
 * rather than remembered from a comment.
 */
class SheetChipSpecTest {
    @Test
    fun `one audio track is not a picker`() {
        SheetChipId.AUDIO.isVisibleIn(state(audioTracks = 1)) shouldBe false
        SheetChipId.AUDIO.isVisibleIn(state(audioTracks = 2)) shouldBe true
    }

    @Test
    fun `subtitles are offered whenever the item has any`() {
        SheetChipId.SUBTITLES.isVisibleIn(state(subtitleTracks = 0)) shouldBe false
        SheetChipId.SUBTITLES.isVisibleIn(state(subtitleTracks = 1)) shouldBe true
    }

    @Test
    fun `the rate is offered only outside a group, and only where the player has one`() {
        SheetChipId.SPEED.isVisibleIn(state(inGroup = false, canSetSpeed = true)) shouldBe true
        // SyncPlay has no per-member rate: playing faster than the group is drifting from it.
        SheetChipId.SPEED.isVisibleIn(state(inGroup = true, canSetSpeed = true)) shouldBe false
        // A receiver that publishes no rate command gets no picker rather than a refused one.
        SheetChipId.SPEED.isVisibleIn(state(inGroup = false, canSetSpeed = false)) shouldBe false
    }

    @Test
    fun `the group chip is exactly membership`() {
        SheetChipId.GROUP.isVisibleIn(state(inGroup = false)) shouldBe false
        SheetChipId.GROUP.isVisibleIn(state(inGroup = true)) shouldBe true
    }

    @Test
    fun `the queue chip waits for the group to actually have a queue`() {
        // Before the first PlayQueueUpdate the sheet would have nothing in it.
        SheetChipId.QUEUE.isVisibleIn(state(inGroup = true, hasQueue = false)) shouldBe false
        SheetChipId.QUEUE.isVisibleIn(state(inGroup = true, hasQueue = true)) shouldBe true
        // Never offered outside a group, whatever a stale hasQueue says.
        SheetChipId.QUEUE.isVisibleIn(state(inGroup = false, hasQueue = true)) shouldBe false
    }

    @Test
    fun `brightness and volume go away while a television has the film`() {
        SheetChipId.DISPLAY.isVisibleIn(state(isCasting = false)) shouldBe true
        SheetChipId.DISPLAY.isVisibleIn(state(isCasting = true)) shouldBe false
    }

    @Test
    fun `a downloaded file has no streaming bitrate to cap`() {
        SheetChipId.QUALITY.isVisibleIn(state(isLocalPlayback = false)) shouldBe true
        SheetChipId.QUALITY.isVisibleIn(state(isLocalPlayback = true)) shouldBe false
    }

    @Test
    fun `every chip opens a panel of its own, and every panel is one a chip opens`() {
        // A chip whose panel is shared with another would open the wrong picker; a panel no chip
        // names would be dead code in an exhaustive `when`.
        val panels = SheetChipId.entries.map { it.panel }

        panels.distinct().size shouldBe SheetChipId.entries.size
        panels.toSet() shouldBe PlayerPanel.entries.toSet()
    }

    @Test
    fun `every chip the bar can draw has a rule, and the row order is the enum's`() {
        // A chip added to the enum without a rule would silently never appear.
        sheetChipSpecs(state()).map { it.id } shouldBe SheetChipId.entries.toList()
    }

    @Test
    fun `the fullest bar is what the width threshold assumes`() {
        // Adding an eighth picker — or loosening a rule so two that currently exclude each other
        // can both appear — fails here.
        everyState().maxOf { visibleSheetChips(it).size } shouldBe MAX_SHEET_CHIPS
    }

    @Test
    fun `the worst case is a group with a queue, with the rate composed out`() {
        val worst =
            state(
                audioTracks = 2,
                subtitleTracks = 1,
                inGroup = true,
                hasQueue = true,
                canSetSpeed = true,
                isCasting = false,
                isLocalPlayback = false,
            )

        visibleSheetChips(worst).map { it.id } shouldBe
            listOf(
                SheetChipId.AUDIO,
                SheetChipId.SUBTITLES,
                SheetChipId.GROUP,
                SheetChipId.QUEUE,
                SheetChipId.DISPLAY,
                SheetChipId.QUALITY,
            )
    }

    @Test
    fun `solo, the fullest bar is the five the device sweep measured`() {
        val solo =
            state(
                audioTracks = 2,
                subtitleTracks = 1,
                inGroup = false,
                canSetSpeed = true,
                isCasting = false,
                isLocalPlayback = false,
            )

        visibleSheetChips(solo).map { it.id } shouldBe
            listOf(
                SheetChipId.AUDIO,
                SheetChipId.SUBTITLES,
                SheetChipId.SPEED,
                SheetChipId.DISPLAY,
                SheetChipId.QUALITY,
            )
    }

    @Test
    fun `the emptiest bar is the clock alone`() {
        visibleSheetChips(
            state(
                audioTracks = 1,
                subtitleTracks = 0,
                canSetSpeed = false,
                isCasting = true,
                isLocalPlayback = true,
            ),
        ) shouldBe emptyList()
    }

    private fun SheetChipId.isVisibleIn(state: PlayerUiState): Boolean =
        sheetChipSpecs(state).single { it.id == this }.visible

    /**
     * Every combination of the seven inputs the rules read — 128 states, impossible ones included.
     *
     * Deliberately unfiltered: casting while in a group cannot happen, but a maximum taken over a
     * *superset* of the reachable states can only over-estimate, which is the safe direction for
     * a width invariant.
     */
    private fun everyState(): List<PlayerUiState> =
        BOOLEANS.flatMap { inGroup ->
            BOOLEANS.flatMap { hasQueue ->
                BOOLEANS.flatMap { canSetSpeed ->
                    BOOLEANS.flatMap { isCasting ->
                        BOOLEANS.flatMap { isLocalPlayback ->
                            BOOLEANS.flatMap { manyAudio ->
                                BOOLEANS.map { anySubtitles ->
                                    state(
                                        audioTracks = if (manyAudio) 2 else 1,
                                        subtitleTracks = if (anySubtitles) 1 else 0,
                                        inGroup = inGroup,
                                        hasQueue = hasQueue,
                                        canSetSpeed = canSetSpeed,
                                        isCasting = isCasting,
                                        isLocalPlayback = isLocalPlayback,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    @Suppress("LongParameterList") // One named argument per rule input; a value type would only wrap it.
    private fun state(
        audioTracks: Int = 2,
        subtitleTracks: Int = 1,
        inGroup: Boolean = false,
        hasQueue: Boolean = false,
        canSetSpeed: Boolean = true,
        isCasting: Boolean = false,
        isLocalPlayback: Boolean = false,
    ) = PlayerUiState(
        audioTracks = List(audioTracks) { PlaybackTrack(index = it, label = "t$it", language = null, codec = null) },
        subtitleTracks =
            List(subtitleTracks) { PlaybackTrack(index = it, label = "s$it", language = null, codec = null) },
        canSetSpeed = canSetSpeed,
        isLocalPlayback = isLocalPlayback,
        syncPlay = PlayerSyncPlayState(inGroup = inGroup, hasQueue = hasQueue),
        cast = PlayerCastState(isCasting = isCasting),
    )

    private companion object {
        val BOOLEANS = listOf(false, true)
    }
}

/**
 * Unit tests for the two pieces of arithmetic behind the seek bar's accessibility: where a
 * custom-action seek lands, and how a position is put into words.
 */
class ScrubberSemanticsTest {
    @Test
    fun `skipping forward moves by the transport's own amount`() {
        seekTargetMs(
            positionMs = 1.minutes.inWholeMilliseconds,
            deltaMs = SKIP_FORWARD_MS,
            durationMs = 45.minutes.inWholeMilliseconds,
        ) shouldBe 90.seconds.inWholeMilliseconds
    }

    @Test
    fun `skipping back near the start lands at the start, not before it`() {
        seekTargetMs(
            positionMs = 4.seconds.inWholeMilliseconds,
            deltaMs = -SKIP_BACK_MS,
            durationMs = 45.minutes.inWholeMilliseconds,
        ) shouldBe 0L
    }

    @Test
    fun `skipping forward near the end lands at the end, not past it`() {
        val duration = 45.minutes.inWholeMilliseconds
        seekTargetMs(
            positionMs = duration - 5.seconds.inWholeMilliseconds,
            deltaMs = SKIP_FORWARD_MS,
            durationMs = duration,
        ) shouldBe duration
    }

    @Test
    fun `an unknown duration still clamps at zero and does not invent an end`() {
        // Duration is 0 until the player reports one; a forward skip there must not be clamped to 0.
        seekTargetMs(positionMs = 0L, deltaMs = -SKIP_BACK_MS, durationMs = 0L) shouldBe 0L
        seekTargetMs(positionMs = 0L, deltaMs = SKIP_FORWARD_MS, durationMs = 0L) shouldBe SKIP_FORWARD_MS
    }

    @Test
    fun `a position under an hour is spoken in minutes and seconds`() {
        (12.minutes + 34.seconds).inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(
                SpokenTimePart(SpokenTimeUnit.MINUTES, 12L),
                SpokenTimePart(SpokenTimeUnit.SECONDS, 34L),
            )
    }

    @Test
    fun `an exact number of minutes does not say zero seconds`() {
        45.minutes.inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(SpokenTimePart(SpokenTimeUnit.MINUTES, 45L))
    }

    @Test
    fun `past an hour the seconds are dropped rather than read out every time`() {
        (1.hours + 3.minutes + 12.seconds).inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(
                SpokenTimePart(SpokenTimeUnit.HOURS, 1L),
                SpokenTimePart(SpokenTimeUnit.MINUTES, 3L),
            )
    }

    @Test
    fun `an exact hour still says its zero minutes, so the unit is not ambiguous`() {
        2.hours.inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(
                SpokenTimePart(SpokenTimeUnit.HOURS, 2L),
                SpokenTimePart(SpokenTimeUnit.MINUTES, 0L),
            )
    }

    @Test
    fun `the very start of a film still says where it is`() {
        0L.asSpokenTimeParts() shouldBe listOf(SpokenTimePart(SpokenTimeUnit.SECONDS, 0L))
    }

    @Test
    fun `a negative position is read as the start rather than as a negative time`() {
        (-5_000L).asSpokenTimeParts() shouldBe listOf(SpokenTimePart(SpokenTimeUnit.SECONDS, 0L))
    }
}
