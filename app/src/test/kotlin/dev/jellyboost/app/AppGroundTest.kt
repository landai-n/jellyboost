package dev.jellyboost.app

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The app has exactly one ground, and for most of M14 it did not: no Compose surface painted
 * `colorScheme.background`, so every screen sat on `themes.xml`'s window background — locked dark
 * because it is painted before Compose and DataStore exist. In the light scheme that put near-black
 * ink on a near-black page on every screen that draws no fill of its own.
 *
 * A rendered check would need an instrumented run, so this pins the source contract the way
 * `ContrastRatioTest`'s mirror list and `AssPreferenceStalenessTest` do: the frame paints the role,
 * the window background stays the documented dark-locked first frame rather than becoming the
 * ground again, and the player keeps its own literal black so the page ground never reaches a
 * letterbox.
 */
class AppGroundTest {
    @Test
    fun `the app's outer frame paints the background role, and paints it once`() {
        val scaffold = read("app/src/main/kotlin/dev/jellyboost/app/AppScaffold.kt")

        scaffold.split(".background(MaterialTheme.colorScheme.background)").size - 1 shouldBe 1
    }

    @Test
    fun `the window background stays dark-locked, so the ground above is what light mode relies on`() {
        val themes = read("app/src/main/res/values/themes.xml")

        themes.contains("""<item name="android:windowBackground">@color/background</item>""") shouldBe true
        Files.exists(repoRoot().resolve("app/src/main/res/values-night")) shouldBe false
    }

    @Test
    fun `the player fills itself, so the page ground never reaches a letterbox`() {
        val player = read("player/src/main/kotlin/dev/jellyboost/player/ui/PlayerScreen.kt")

        player.contains(".background(Color.Black)") shouldBe true
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
