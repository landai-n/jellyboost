package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
