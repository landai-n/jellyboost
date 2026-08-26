package dev.jellyboost.feature.settings

import android.content.Context
import android.content.res.Resources
import app.cash.turbine.test
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class LicenceViewModelTest {
    private val resources = mockk<Resources>()
    private val context = mockk<Context>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { context.resources } returns resources
    }

    private fun viewModelReading(text: String): LicenceViewModel {
        every { resources.openRawResource(any()) } returns ByteArrayInputStream(text.toByteArray())
        return LicenceViewModel(context, UnconfinedTestDispatcher())
    }

    @Test
    @DisplayName("the bundled text reaches the screen as paragraphs, hard wrapping undone")
    fun paragraphsReachTheScreen() =
        runTest {
            val viewModel =
                viewModelReading(
                    """
                    |                    GNU GENERAL PUBLIC LICENSE
                    |
                    |  The GNU General Public License is a free, copyleft license for
                    |software and other kinds of works.
                    """.trimMargin(),
                )

            viewModel.blocks.test {
                awaitItem() shouldContainExactly
                    listOf(
                        "GNU GENERAL PUBLIC LICENSE",
                        "The GNU General Public License is a free, copyleft license for software and " +
                            "other kinds of works.",
                    )
            }
        }

    @Test
    @DisplayName("a licence that cannot be read leaves the screen empty rather than taking the app down")
    fun anUnreadableLicenceIsSurvivable() =
        runTest {
            every { resources.openRawResource(any()) } throws Resources.NotFoundException("raw/gpl_3_0")
            val viewModel = LicenceViewModel(context, UnconfinedTestDispatcher())

            viewModel.blocks.test {
                awaitItem() shouldBe emptyList()
                expectNoEvents()
            }
        }

    @Test
    @DisplayName("the packaged licence is the repository's LICENSE, byte for byte")
    fun theBundledCopyIsNotAParaphrase() {
        val bundled = File("src/main/res/raw/gpl_3_0.txt")
        val canonical = File("../../LICENSE")

        bundled.readText() shouldBe canonical.readText()
    }
}

class LicenceBlocksTest {
    @Test
    @DisplayName("a deeply indented line is a heading and stands alone")
    fun headingsStandAlone() {
        licenceBlocks("            Preamble\nprose that follows it") shouldContainExactly
            listOf("Preamble", "prose that follows it")
    }

    @Test
    @DisplayName("hard-wrapped prose joins into one paragraph")
    fun wrappedProseJoins() {
        licenceBlocks("  A first line\nand its continuation.") shouldContainExactly
            listOf("A first line and its continuation.")
    }

    @Test
    @DisplayName("a blank line ends the paragraph")
    fun blankLinesSeparate() {
        licenceBlocks("First one.\n\nSecond one.") shouldContainExactly listOf("First one.", "Second one.")
    }

    @Test
    @DisplayName("the whole document survives the reflow — nothing is dropped")
    fun nothingIsLost() {
        val licence = File("src/main/res/raw/gpl_3_0.txt").readText()

        val reflowed = licenceBlocks(licence).joinToString(" ").replace(Regex("\\s+"), " ")
        val original = licence.replace(Regex("\\s+"), " ").trim()

        reflowed shouldBe original
    }
}
