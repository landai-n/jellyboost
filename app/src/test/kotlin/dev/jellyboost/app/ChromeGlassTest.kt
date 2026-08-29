package dev.jellyboost.app

import androidx.compose.ui.graphics.Color
import dev.jellyboost.core.ui.theme.OverMedia
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The chrome's two grounds — the page's glass and the artwork's — and the crossing between them.
 * An `if` on the boolean repainted the circles in one frame under a page the user was still watching
 * fade; the fraction is what removes that, and pinning both ends is what stops a future edit from
 * lerping one colour and leaving another behind (`GhostPillButton`'s half-fix, on this axis).
 */
class ChromeGlassTest {
    @Test
    @DisplayName("on the page every colour is the scheme's")
    fun onThePage() {
        val glass = glassAt(0f)

        glass.fill shouldBe PageFill
        glass.border shouldBe PageBorder
        glass.content shouldBe PageContent
        glass.error shouldBe PageError
    }

    @Test
    @DisplayName("over artwork every colour is OverMedia's — none left behind")
    fun overArtwork() {
        val glass = glassAt(1f)

        glass.fill shouldBe OverMedia.ChromeFill
        glass.border shouldBe OverMedia.ChromeBorder
        glass.content shouldBe OverMedia.GlassContent
        glass.error shouldBe OverMedia.ErrorAccent
    }

    @Test
    @DisplayName("mid-crossing no colour has arrived yet")
    fun midCrossing() {
        val glass = glassAt(0.5f)

        listOf(
            glass.fill to (PageFill to OverMedia.ChromeFill),
            glass.border to (PageBorder to OverMedia.ChromeBorder),
            glass.content to (PageContent to OverMedia.GlassContent),
            glass.error to (PageError to OverMedia.ErrorAccent),
        ).forEach { (drawn, ends) ->
            val (from, to) = ends
            check(drawn != from && drawn != to) { "$drawn snapped instead of crossing" }
        }
    }

    private fun glassAt(ground: Float) =
        chromeGlassAt(
            ground = ground,
            pageFill = PageFill,
            pageBorder = PageBorder,
            pageContent = PageContent,
            pageError = PageError,
        )

    private companion object {
        val PageFill = Color(0xB8EEF1F7)
        val PageBorder = Color(0x1A000000)
        val PageContent = Color(0xCC191B22)
        val PageError = Color(0xFFB3261E)
    }
}
