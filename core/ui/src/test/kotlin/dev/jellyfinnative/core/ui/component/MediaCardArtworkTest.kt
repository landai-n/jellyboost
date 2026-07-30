package dev.jellyfinnative.core.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [cardWidth] — one of `:core:ui`'s first (docs/notes/audit-2026-07.md, ARCH-07; this
 * module had none before it).
 *
 * Every card (`LibraryCard`, `PosterCard`, `ThumbCard`) starts its modifier chain with this, so
 * getting the fixed-vs-adaptive branch backwards would be visible on every grid and every row in the
 * app. Both branches delegate to a plain layout modifier, so the fixed-width and fill-width forms are
 * asserted against the exact modifier they should be indistinguishable from — no composition needed,
 * since building a `Modifier` chain is plain object construction.
 */
class MediaCardArtworkTest {
    @Test
    fun `a specified width is a fixed width, same as calling width directly`() {
        Modifier.cardWidth(120.dp) shouldBe Modifier.width(120.dp)
    }

    @Test
    fun `Unspecified fills the available width instead`() {
        Modifier.cardWidth(Dp.Unspecified) shouldBe Modifier.fillMaxWidth()
    }

    @Test
    fun `a fixed width is not mistaken for filling the width`() {
        (Modifier.cardWidth(120.dp) == Modifier.fillMaxWidth()) shouldBe false
    }
}
