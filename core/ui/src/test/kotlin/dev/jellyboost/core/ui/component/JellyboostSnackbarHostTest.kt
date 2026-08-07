package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the two rules [JellyboostSnackbarHost] took over from five hand-written hosts
 * (audit DUP-3 + HYG-8): where the pill sits, and when it fires.
 */
class JellyboostSnackbarHostTest {
    // ---- where the pill sits ---------------------------------------------------------------------

    @Test
    @DisplayName("a top-level screen floats the snackbar above the nav pill")
    fun chromeBottomWinsWhenThePillIsUp() {
        val chrome = PaddingValues(bottom = 104.dp)

        SnackbarBottomInset(chrome, navigationBarInset = 24.dp).calculateBottomPadding() shouldBe 104.dp
    }

    @Test
    @DisplayName("a pushed destination falls back to the navigation-bar inset")
    fun navigationBarInsetWinsWhenThereIsNoChrome() {
        // Every pushed destination: `LocalAppChromePadding` is zero by contract, and this is the
        // value the hand-written `navigationBarsPadding()` was applying.
        SnackbarBottomInset(PaddingValues(bottom = 0.dp), navigationBarInset = 24.dp)
            .calculateBottomPadding() shouldBe 24.dp
    }

    @Test
    @DisplayName("a wide layout keeps the gesture-bar inset the chrome padding no longer carries")
    fun wideLayoutKeepsTheGestureBarInset() {
        // On a wide window the chrome is all at the top, so its bottom padding is zero. A screen
        // that read only the chrome padding — the downloads screen did — put its snackbar under
        // the gesture bar on exactly the tablet this app is tested on.
        SnackbarBottomInset(PaddingValues(bottom = 0.dp), navigationBarInset = 48.dp)
            .calculateBottomPadding() shouldBe 48.dp
    }

    @Test
    @DisplayName("the snackbar never dips under the gesture bar while the chrome animates away")
    fun theInsetIsAFloorDuringATransition() {
        SnackbarBottomInset(PaddingValues(bottom = 9.dp), navigationBarInset = 24.dp)
            .calculateBottomPadding() shouldBe 24.dp
    }

    @Test
    @DisplayName("an immersive screen can raise the floor for chrome it drew itself")
    fun minimumInsetRaisesTheFloor() {
        // The player: no system insets, no app chrome, but its own transport controls to clear.
        SnackbarBottomInset(PaddingValues(bottom = 0.dp), navigationBarInset = 0.dp, minimumInset = 72.dp)
            .calculateBottomPadding() shouldBe 72.dp
        // …and it never *lowers* one that is already larger.
        SnackbarBottomInset(PaddingValues(bottom = 104.dp), navigationBarInset = 0.dp, minimumInset = 72.dp)
            .calculateBottomPadding() shouldBe 104.dp
    }

    @Test
    @DisplayName("the snackbar inset is bottom-only")
    fun theInsetIsBottomOnly() {
        val inset = SnackbarBottomInset(PaddingValues(bottom = 104.dp), navigationBarInset = 24.dp)

        inset.calculateTopPadding() shouldBe 0.dp
        inset.calculateLeftPadding(LayoutDirection.Ltr) shouldBe 0.dp
        inset.calculateRightPadding(LayoutDirection.Ltr) shouldBe 0.dp
        inset.calculateLeftPadding(LayoutDirection.Rtl) shouldBe 0.dp
        inset.calculateRightPadding(LayoutDirection.Rtl) shouldBe 0.dp
    }

    // ---- when the pill fires (HYG-8) -------------------------------------------------------------

    /**
     * Two distinct messages that happen to share their copy, arriving back to back with no `null`
     * between them — a batch finishing while a failure is still on screen.
     */
    private val sharedCopyBurst = listOf(Message.DeleteFailed, Message.ActionFailed)

    private val copy: (Message) -> String =
        { message ->
            when (message) {
                // Deliberately identical: this is the case the copy-keyed effect could not see.
                Message.DeleteFailed -> "Something went wrong"
                Message.ActionFailed -> "Something went wrong"
                Message.Queued -> "Added to downloads"
            }
        }

    @Test
    @DisplayName("two distinct messages sharing copy are both shown and both consumed")
    fun sharedCopyMessagesBothFire() {
        val run = replayOneShot(sharedCopyBurst, key = ::oneShotSnackbarKey, text = copy)

        run.shown shouldBe listOf("Something went wrong", "Something went wrong")
        run.consumed shouldBe 2
    }

    @Test
    @DisplayName("keying on the resolved copy wedges the second message — the bug this replaces")
    fun keyingOnCopyWedgesTheSecondMessage() {
        // The characterization of HYG-8, kept so the fix cannot be quietly undone: the second
        // message is never shown and — worse — never consumed, so the field stays non-null and the
        // screen can never show another snackbar again.
        val run = replayOneShot(sharedCopyBurst, key = { it?.let(copy) }, text = copy)

        run.shown shouldBe listOf("Something went wrong")
        run.consumed shouldBe 1
    }

    @Test
    @DisplayName("the same message shown twice, with a consume between, fires twice")
    fun theSameMessageTwiceFiresTwice() {
        val run =
            replayOneShot(
                listOf(Message.Queued, null, Message.Queued),
                key = ::oneShotSnackbarKey,
                text = copy,
            )

        run.shown shouldBe listOf("Added to downloads", "Added to downloads")
        run.consumed shouldBe 2
    }

    @Test
    @DisplayName("a recomposition that changes nothing does not re-show the message")
    fun anUnchangedMessageDoesNotRefire() {
        val run =
            replayOneShot(
                listOf(Message.Queued, Message.Queued, Message.Queued),
                key = ::oneShotSnackbarKey,
                text = copy,
            )

        run.shown shouldBe listOf("Added to downloads")
        run.consumed shouldBe 1
    }

    @Test
    @DisplayName("no message shows nothing and consumes nothing")
    fun noMessageDoesNothing() {
        val run = replayOneShot(listOf(null, null), key = ::oneShotSnackbarKey, text = copy)

        run.shown shouldBe emptyList()
        run.consumed shouldBe 0
    }

    /** Stand-in for the message types the five screens hold — the shape, not any one screen's. */
    private enum class Message { DeleteFailed, ActionFailed, Queued }

    private data class OneShotRun(
        val shown: List<String>,
        val consumed: Int,
    )

    /**
     * Replays `LaunchedEffect`'s restart contract over a sequence of message states.
     *
     * This module has no Robolectric and no composition harness, so the one property under test is
     * modelled directly: an effect body runs when — and only when — its key differs from the key it
     * last ran with. [key] is the production function under test, so a change to it changes what
     * this measures.
     */
    private fun <T : Any> replayOneShot(
        states: List<T?>,
        key: (T?) -> Any?,
        text: (T) -> String,
    ): OneShotRun {
        val shown = mutableListOf<String>()
        var consumed = 0
        var lastKey: Any? = Unit // Never equal to a real key, so the first state always runs.

        states.forEach { message ->
            val currentKey = key(message)
            if (currentKey == lastKey) return@forEach
            lastKey = currentKey
            if (message != null) {
                shown += text(message)
                consumed++
            }
        }
        return OneShotRun(shown = shown, consumed = consumed)
    }
}
