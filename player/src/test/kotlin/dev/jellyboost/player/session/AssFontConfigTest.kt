package dev.jellyboost.player.session

import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What `AssFontConfig`'s KDoc claims, held to the parts a JVM test can reach. The glyphs themselves
 * are device-owed — check (2) of `docs/notes/m14-ass-libass-spike.md` — but the document libass is
 * handed, and the order in which it is handed over, are decided here and pinned here.
 */
class AssFontConfigTest {
    @Test
    fun `the document names the Android font directories, so fontconfig has somewhere to scan`() {
        val document = AssFontConfig.document(File("/cache/libass-fontconfig"))

        AssFontConfig.FONT_DIRECTORIES.forEach { document shouldContain "<dir>$it</dir>" }
        document shouldContain "<dir>/system/fonts</dir>"
    }

    @Test
    fun `the document aliases sans-serif, which is the only family libass ever asks fontconfig for`() {
        val document = AssFontConfig.document(File("/cache/libass-fontconfig"))

        document shouldContain "<family>sans-serif</family>"
        document shouldContain "<prefer>"
        // Preference order is the alias's whole point: an arbitrary sort is the bug being fixed.
        document.lines().mapNotNull { line ->
            Regex("<family>(.+)</family>").find(line.trim())?.groupValues?.get(1)
        } shouldContainInOrder listOf("sans-serif") + AssFontConfig.SANS_SERIF_FAMILIES
    }

    @Test
    fun `the cache directory is one fontconfig can write, so the first subtitle does not pay a full scan`(
        @TempDir temp: File,
    ) {
        val cacheDir = File(temp, "cache")
        val config = AssFontConfig.install(filesDir = File(temp, "files"), cacheDir = cacheDir)

        val fontCacheDir = File(cacheDir, "libass-fontconfig")
        fontCacheDir.isDirectory shouldBe true
        fontCacheDir.canWrite() shouldBe true
        config.readText() shouldContain "<cachedir>${fontCacheDir.absolutePath}</cachedir>"
    }

    @Test
    fun `an unchanged install leaves the file alone, so a cache fontconfig already built survives`(
        @TempDir temp: File,
    ) {
        val filesDir = File(temp, "files")
        val cacheDir = File(temp, "cache")

        val first = AssFontConfig.install(filesDir, cacheDir)
        val writtenAt = first.lastModified()
        first.setLastModified(writtenAt - MINUTE_MILLIS)

        val second = AssFontConfig.install(filesDir, cacheDir)

        second.absolutePath shouldBe first.absolutePath
        second.lastModified() shouldBe writtenAt - MINUTE_MILLIS
    }

    /**
     * `FONTCONFIG_FILE` is read exactly once, inside the `ass_set_fonts` call `AssHandler` makes
     * while building its renderer. Setting it after the handler exists would be a no-op that still
     * looked correct in a diff, and no test that can run off a device would catch it — an
     * `AssHandler` cannot be constructed without the native library.
     */
    @Test
    fun `the environment is set before the handler that reads it is constructed`() {
        val source = read("player/src/main/kotlin/dev/jellyboost/player/session/AssSubtitleSupport.kt")

        val install = source.indexOf("            installFontConfig()")
        val handler = source.indexOf("AssHandler(AssRenderType")
        check(install >= 0) { "`installFontConfig()` is no longer called from `createHandler`" }
        check(handler >= 0) { "the handler construction moved — this test's subject moved, not its claim" }
        (install < handler) shouldBe true
        source shouldContain "Os.setenv(AssFontConfig.ENVIRONMENT_VARIABLE"
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

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
