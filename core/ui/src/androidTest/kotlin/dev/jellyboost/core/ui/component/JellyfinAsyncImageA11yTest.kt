package dev.jellyboost.core.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The placeholder path keeps the caller's label (accessibility audit 2026-08-05, "JellyfinAsyncImage
 * drops the caller's contentDescription on the placeholder path").
 *
 * Wave 2 fixed this and wave 4b reported it as still broken — one of the two had to be wrong, and
 * the only way to settle it permanently is a test rather than a reading of the file. An item with no
 * artwork is the case that matters: a cast rail or a queue row draws no text beside the picture, so
 * an unlabelled placeholder is a person who is simply not there.
 */
@RunWith(AndroidJUnit4::class)
class JellyfinAsyncImageA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun anItemWithNoArtworkStillCarriesTheCallersLabel() {
        rule.setContent {
            JellyfinTheme {
                JellyfinAsyncImage(
                    url = null,
                    contentDescription = PERSON,
                    modifier = Modifier.size(SIZE),
                )
            }
        }

        rule.onNodeWithContentDescription(PERSON).assertExists()
    }

    @Test
    fun aBlankUrlIsTreatedAsNoArtworkRatherThanAsAnImage() {
        rule.setContent {
            JellyfinTheme {
                JellyfinAsyncImage(
                    url = "  ",
                    contentDescription = PERSON,
                    modifier = Modifier.size(SIZE),
                )
            }
        }

        rule.onNodeWithContentDescription(PERSON).assertExists()
    }

    @Test
    fun decorativeArtworkStaysUnlabelled() {
        // What every card passes: the merged card node owns the sentence, so the slot must add
        // nothing — a described placeholder inside a merged card would append a second name to it.
        rule.setContent {
            JellyfinTheme {
                JellyfinAsyncImage(
                    url = null,
                    contentDescription = null,
                    modifier = Modifier.size(SIZE),
                )
            }
        }

        rule.onAllNodes(hasAnyContentDescription()).assertCountEquals(0)
    }

    private fun hasAnyContentDescription() =
        SemanticsMatcher("has a contentDescription") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
        }

    private companion object {
        const val PERSON = "Amy Adams"
        val SIZE = 64.dp
    }
}
