package dev.jellyboost.player.session

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 * The one invariant `PlaybackHandover` exists for: **exactly one stop report per session, issued
 * by the outgoing owner, completed before the claimant is allowed to prepare.**
 *
 * Everything here is ordering, which is why it is asserted as a transcript rather than as call
 * counts: "the music stop landed before the video claim returned" is the property, and only the
 * sequence shows it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHandoverTest {
    private val transcript = mutableListOf<String>()

    @Test
    fun `the first claim runs no relinquish`() =
        runTest {
            val handover = PlaybackHandover()

            handover.claim(PlaybackKind.MUSIC) { transcript += "music relinquished" }

            transcript.shouldContainExactly()
            handover.currentOwner shouldBe PlaybackKind.MUSIC
        }

    @Test
    fun `a claim by the other kind relinquishes the owner first, and completes before it returns`() =
        runTest {
            val handover = PlaybackHandover()
            handover.claim(PlaybackKind.MUSIC) {
                yield()
                transcript += "music stop report"
            }

            handover.claim(PlaybackKind.VIDEO) { transcript += "video relinquished" }
            transcript += "video prepared"

            transcript.shouldContainExactly("music stop report", "video prepared")
            handover.currentOwner shouldBe PlaybackKind.VIDEO
        }

    @Test
    fun `re-claiming for the same kind is not a handover`() =
        runTest {
            val handover = PlaybackHandover()
            handover.claim(PlaybackKind.VIDEO) { transcript += "first relinquish" }

            handover.claim(PlaybackKind.VIDEO) { transcript += "second relinquish" }

            transcript.shouldContainExactly()
        }

    @Test
    fun `two claimants racing the same owner relinquish it exactly once`() =
        runTest {
            val handover = PlaybackHandover()
            handover.claim(PlaybackKind.VIDEO) {
                yield()
                transcript += "video stop report"
            }

            launch { handover.claim(PlaybackKind.MUSIC) { transcript += "music A relinquished" } }
            launch { handover.claim(PlaybackKind.MUSIC) { transcript += "music B relinquished" } }
            advanceUntilIdle()

            transcript.shouldContainExactly("video stop report")
            handover.currentOwner shouldBe PlaybackKind.MUSIC
        }

    @Test
    fun `releasing disowns the claim, so a later claim relinquishes nothing`() =
        runTest {
            val handover = PlaybackHandover()
            handover.claim(PlaybackKind.VIDEO) { transcript += "stale video stop report" }

            handover.release(PlaybackKind.VIDEO)
            handover.claim(PlaybackKind.MUSIC) { transcript += "music relinquished" }

            transcript.shouldContainExactly()
            handover.currentOwner shouldBe PlaybackKind.MUSIC
        }

    @Test
    fun `releaseNow disowns the claim from a caller that cannot suspend`() =
        runTest {
            val handover = PlaybackHandover()
            handover.claim(PlaybackKind.VIDEO) { transcript += "stale video stop report" }

            handover.releaseNow(PlaybackKind.VIDEO) shouldBe true
            handover.claim(PlaybackKind.MUSIC) { transcript += "music relinquished" }

            transcript.shouldContainExactly()
        }

    @Test
    fun `releasing a kind that does not own the player leaves the owner alone`() =
        runTest {
            val handover = PlaybackHandover()
            handover.claim(PlaybackKind.MUSIC) { transcript += "music stop report" }

            // The video screen tearing down long after music took the player over.
            handover.release(PlaybackKind.VIDEO)
            handover.claim(PlaybackKind.VIDEO) { transcript += "video relinquished" }

            transcript.shouldContainExactly("music stop report")
        }
}
