package dev.jellyboost.player.session

import java.io.File

/**
 * The fontconfig configuration libass is pointed at, because the one compiled into `ass-kt` 0.5.1
 * cannot exist on a device.
 *
 * `libass.so` is built on CI with fontconfig's `baseconfigdir`, `conf.avail` and `cachedir` left at
 * their build-tree defaults, so the shipped binary looks for `fonts.conf` under a
 * `/home/runner/work/...` path. On Android that path is absent, fontconfig reports "No usable
 * fontconfig configuration file found, using fallback" and falls back to a built-in document that
 * carries `/system/fonts` and `/product/fonts` but **no `conf.d` at all** — and an equally absent
 * cache directory.
 *
 * Losing `conf.d` loses the generic-family rules, and `sans-serif` is exactly what libass asks for:
 * `AssKt.c` calls `ass_set_fonts(renderer, NULL, "sans-serif", ASS_FONTPROVIDER_FONTCONFIG, NULL, 1)`,
 * and libass's fontconfig provider builds its whole fallback list by running `FcFontSort` over a
 * pattern whose only family is `sans-serif` (`ass_fontconfig.c: cache_fallbacks`). With no alias to
 * resolve, that sort degenerates into an arbitrary ordering of every font on the device, and
 * `get_fallback` then serves each codepoint from the first entry that covers it. Letters are only
 * covered by real text fonts, so they still come out right — but U+0020 is covered by nearly
 * everything installed, including icon and clock faces whose space advance is zero. The visible
 * result is styled, correct-looking glyphs with no gaps between the words: the device report this
 * class exists to answer.
 *
 * Writing a configuration and naming it in `FONTCONFIG_FILE` is the same remedy ffmpeg-kit ships for
 * the same platform gap. The `sans-serif` alias is the load-bearing part; the cache directory is
 * what keeps the first subtitle of a session from paying a full uncached scan of `/system/fonts`.
 */
internal object AssFontConfig {
    /** Ignored by fontconfig when absent, so the OEM-specific ones cost nothing on a device without them. */
    val FONT_DIRECTORIES =
        listOf(
            "/system/fonts",
            "/product/fonts",
            "/system_ext/fonts",
            "/system/font",
            "/data/fonts",
        )

    /**
     * Preferred in order, and every entry is a real family name: an alias whose targets are all
     * missing would leave the sort as arbitrary as it is today. `Roboto` is on every Android build,
     * the rest are what older and Noto-only images carry.
     */
    val SANS_SERIF_FAMILIES = listOf("Roboto", "Noto Sans", "Droid Sans", "DejaVu Sans")

    private const val CONFIG_DIRECTORY = "libass"
    private const val CONFIG_FILE = "fonts.conf"
    private const val CACHE_DIRECTORY = "libass-fontconfig"

    /** The environment variable fontconfig reads in `FcInitLoadConfig`, which is what libass calls. */
    const val ENVIRONMENT_VARIABLE = "FONTCONFIG_FILE"

    fun document(fontCacheDir: File): String =
        buildString {
            appendLine("<?xml version=\"1.0\"?>")
            appendLine("<!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\">")
            appendLine("<fontconfig>")
            FONT_DIRECTORIES.forEach { appendLine("  <dir>$it</dir>") }
            appendLine("  <cachedir>${fontCacheDir.absolutePath}</cachedir>")
            appendLine("  <alias>")
            appendLine("    <family>sans-serif</family>")
            appendLine("    <prefer>")
            SANS_SERIF_FAMILIES.forEach { appendLine("      <family>$it</family>") }
            appendLine("    </prefer>")
            appendLine("  </alias>")
            appendLine("</fontconfig>")
        }

    /**
     * Writes the configuration under [filesDir] and returns it, creating the cache directory named
     * inside it under [cacheDir]. Rewritten only when its content moves, so an unchanged install
     * does not invalidate a cache fontconfig has already built.
     */
    fun install(
        filesDir: File,
        cacheDir: File,
    ): File {
        val fontCacheDir = File(cacheDir, CACHE_DIRECTORY)
        fontCacheDir.mkdirs()
        val configDir = File(filesDir, CONFIG_DIRECTORY)
        configDir.mkdirs()

        val config = File(configDir, CONFIG_FILE)
        val document = document(fontCacheDir)
        if (!config.isFile || config.readText() != document) {
            config.writeText(document)
        }
        return config
    }
}
