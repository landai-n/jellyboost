package dev.jellyboost.player.ui

import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The rule lives in an explicit [ControlsAutoHide] value rather than a `LaunchedEffect`'s key
 * list: a key of `(shouldHide, timeoutMs)` alone would mean nothing a user *did* with the controls
 * restarted the timer — the bar would hide four seconds after it first appeared whether it had
 * been used or ignored. Both halves are pinned here: [ControlsAutoHide] `armed` for the four
 * things that stop the timer, and the value's own equality for the one thing that restarts it,
 * since equality is exactly what Compose asks of an effect key.
 */
class ControlsAutoHideTest {
    @Test
    fun `visible controls over a playing film count down`() {
        timer().armed shouldBe true
    }

    @Test
    fun `controls that are already away have nothing to hide`() {
        timer(visible = false).armed shouldBe false
    }

    @Test
    fun `a paused film keeps its controls`() {
        // A paused film with no controls looks like a frozen app.
        timer(isPlaying = false).armed shouldBe false
    }

    @Test
    fun `an open panel suspends the timer`() {
        // A user with a picker open is using the player; dismissing it must not reveal bare video.
        timer(panelOpen = true).armed shouldBe false
    }

    @Test
    fun `touch exploration suspends the timer entirely`() {
        // Four seconds is not a traversal, and no finite timeout is long enough to read every
        // control one element at a time.
        timer(touchExplorationEnabled = true).armed shouldBe false
    }

    @Test
    fun `an interaction restarts the timer`() {
        // The key changes, so Compose cancels the running delay and a fresh four seconds begin.
        val before = timer(interactions = 3)
        val after = timer(interactions = 4)

        after.armed shouldBe true
        after shouldNotBe before
    }

    @Test
    fun `nothing happening does not restart the timer`() {
        // A recomposition that changes none of the inputs must leave the countdown alone, or the
        // controls would never hide at all.
        timer(interactions = 3) shouldBe timer(interactions = 3)
    }

    @Test
    fun `a longer accessibility timeout is honoured, and restarts the timer`() {
        // "Time to take action" reaches the player as a timeout rather than as a flag, so a user who
        // changes it mid-film gets the new value on the next countdown rather than at the next film.
        val longer = timer(timeoutMs = 20_000L)

        longer.timeoutMs shouldBe 20_000L
        longer shouldNotBe timer()
    }

    @Test
    fun `an interaction while the timer is suspended still does not arm it`() {
        // Interaction restarts; it does not override the suspension.
        timer(touchExplorationEnabled = true, interactions = 9).armed shouldBe false
    }

    /** The effect's key, assembled exactly as `ControlsAutoHideEffect` assembles it. */
    @Suppress("LongParameterList") // One named argument per input to the rule under test.
    private fun timer(
        visible: Boolean = true,
        isPlaying: Boolean = true,
        panelOpen: Boolean = false,
        touchExplorationEnabled: Boolean = false,
        timeoutMs: Long = 4_000L,
        interactions: Int = 0,
    ) = ControlsAutoHide(
        armed =
            controlsAutoHideArmed(
                visible = visible,
                isPlaying = isPlaying,
                panelOpen = panelOpen,
                touchExplorationEnabled = touchExplorationEnabled,
            ),
        timeoutMs = timeoutMs,
        interactions = interactions,
    )
}

/**
 * The test that matters is the last one: it invokes *every* lambda in the bundle and insists each
 * one both reported and forwarded. An action added to [PlayerActions] and forgotten in the wrapper
 * fails it — the alternative, a bump at each call site, is exactly the kind of rule that holds
 * until the next feature.
 */
class PlayerActionsInteractionTest {
    private val forwarded = mutableListOf<String>()

    @Test
    fun `an action reaches what it was pointed at, with its argument intact`() {
        var interactions = 0
        val actions = recording().reportingInteraction { interactions++ }

        actions.onSeekTo(42L)

        forwarded shouldBe listOf("seekTo:42")
        interactions shouldBe 1
    }

    @Test
    fun `the report happens before the action, not after it`() {
        // Order is load-bearing: the action may open a panel, which suspends the timer — the restart
        // has to be recorded against the state the tap arrived in.
        val order = mutableListOf<String>()
        val actions =
            PlayerActions(
                onPlayPause = { order += "action" },
                onSeekTo = {},
                onSeekBy = {},
                onSelectAudio = {},
                onSelectSubtitle = {},
                onSelectQuality = {},
                onSelectSpeed = {},
                onSkipSegment = {},
                onPlayNext = {},
                onDismissUpNext = {},
                onBack = {},
            ).reportingInteraction { order += "report" }

        actions.onPlayPause()

        order shouldBe listOf("report", "action")
    }

    @Test
    fun `every action in the bundle reports an interaction and forwards`() {
        var interactions = 0
        val actions = recording().reportingInteraction { interactions++ }
        val invocations: List<Pair<String, () -> Unit>> =
            listOf(
                "playPause" to { actions.onPlayPause() },
                "seekTo:1" to { actions.onSeekTo(1L) },
                "seekBy:2" to { actions.onSeekBy(2L) },
                "audio:3" to { actions.onSelectAudio(3) },
                "subtitle:4" to { actions.onSelectSubtitle(4) },
                "quality:AUTO" to { actions.onSelectQuality(PlaybackQuality.AUTO) },
                "speed:NORMAL" to { actions.onSelectSpeed(PlaybackSpeed.NORMAL) },
                "skipSegment" to { actions.onSkipSegment() },
                // The up-next card's two: a user reading it is still here, so the controls must not
                // hide out from under the decision they are making.
                "playNext" to { actions.onPlayNext() },
                "dismissUpNext" to { actions.onDismissUpNext() },
                "back" to { actions.onBack() },
                "panel:AUDIO" to { actions.onOpenPanel(PlayerPanel.AUDIO) },
                "shuffle:true" to { actions.onSetGroupShuffle(true) },
                "repeat:None" to { actions.onSetGroupRepeat(SyncPlayRepeatMode.None) },
                "leaveGroup" to { actions.onLeaveGroup() },
            )

        invocations.forEach { (_, invoke) -> invoke() }

        forwarded shouldBe invocations.map { (expected, _) -> expected }
        interactions shouldBe invocations.size
    }

    private fun recording() =
        PlayerActions(
            onPlayPause = { forwarded += "playPause" },
            onSeekTo = { position -> forwarded += "seekTo:$position" },
            onSeekBy = { delta -> forwarded += "seekBy:$delta" },
            onSelectAudio = { index -> forwarded += "audio:$index" },
            onSelectSubtitle = { index -> forwarded += "subtitle:$index" },
            onSelectQuality = { quality -> forwarded += "quality:$quality" },
            onSelectSpeed = { speed -> forwarded += "speed:$speed" },
            onSkipSegment = { forwarded += "skipSegment" },
            onPlayNext = { forwarded += "playNext" },
            onDismissUpNext = { forwarded += "dismissUpNext" },
            onBack = { forwarded += "back" },
            onOpenPanel = { panel -> forwarded += "panel:$panel" },
            onSetGroupShuffle = { shuffle -> forwarded += "shuffle:$shuffle" },
            onSetGroupRepeat = { mode -> forwarded += "repeat:$mode" },
            onLeaveGroup = { forwarded += "leaveGroup" },
        )
}
