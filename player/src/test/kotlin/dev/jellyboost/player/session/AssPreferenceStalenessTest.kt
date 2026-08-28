package dev.jellyboost.player.session

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The styled-ASS switch tells the user it "applies to the next video you start with nothing else
 * playing", and `playback.md`, the spike note and `AssSubtitleSupport`'s KDoc all explain the
 * condition. Nothing else in the suite can hold them to it: the read happens inside
 * `ExoPlayerHandle.buildPlayer`, and an `ExoPlayer` cannot be constructed off a device.
 *
 * So this pins the two source facts the copy rests on, the way `ContrastRatioTest`'s mirror list
 * pins tokens it cannot import. **If a future change rebuilds the player when the preference moves,
 * this test fails — and the copy, the docs and the DECISIONS entry are what it is asking you to
 * change with it.**
 */
class AssPreferenceStalenessTest {
    @Test
    fun `the preference is read in exactly one place, and that place is the player build`() {
        val source = read("player/src/main/kotlin/dev/jellyboost/player/session/ExoPlayerHandle.kt")

        source.split("assSubtitles.createHandler()").size - 1 shouldBe 1
        bodyOf(source, "private fun buildPlayer()").contains("assSubtitles.createHandler()") shouldBe true
    }

    @Test
    fun `an existing player is reused rather than rebuilt, so a later preference change cannot reach it`() {
        val source = read("player/src/main/kotlin/dev/jellyboost/player/session/ExoPlayerHandle.kt")

        source.contains("fun requirePlayer(): ExoPlayer = exoPlayer ?: buildPlayer().also { exoPlayer = it }") shouldBe
            true
    }

    @Test
    fun `the music handover releases the adapter and not the player, which is what makes the wait real`() {
        val source = read("player/src/main/kotlin/dev/jellyboost/player/music/MusicPlaybackController.kt")
        val relinquish = bodyOf(source, "private suspend fun relinquishToOther()")

        relinquish.contains("onPlayer { release() }") shouldBe true
        // Its own comment names the alternative, so the check is on the call and not the word.
        relinquish.contains("onPlayer { stopAndRelease") shouldBe false
    }

    /** From [signature] to the first line that closes its top-level brace at the signature's indent. */
    private fun bodyOf(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "`$signature` is gone — this test's subject moved, not its claim" }
        val indent = " ".repeat(source.lastIndexOf('\n', start).let { start - it - 1 })
        val end = source.indexOf("\n$indent}", start)
        check(end > start) { "could not find the end of `$signature`" }
        return source.substring(start, end)
    }

    private fun read(path: String): String {
        val file = repoRoot().resolve(path)
        check(Files.exists(file)) { "$path no longer exists — update this test's subjects" }
        return Files.readString(file)
    }

    /** Fails loudly rather than skipping: a source check that reads nothing looks like coverage. */
    private fun repoRoot(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("could not locate the repository root from ${Paths.get("").toAbsolutePath()}")
    }
}
