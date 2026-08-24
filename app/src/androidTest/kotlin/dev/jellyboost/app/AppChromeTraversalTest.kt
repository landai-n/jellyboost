package dev.jellyboost.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reading order of the app's chrome. The failure this guards against — a dropped traversal
 * modifier or a drifting index — leaves no visible trace at all.
 *
 * The scaffold itself is not composed: it resolves two `hiltViewModel()`s and a `NavHost`, so it
 * would need a signed-in session and a reachable server. The arrangement below wears the same three
 * modifiers its call sites use.
 */
@RunWith(AndroidJUnit4::class)
class AppChromeTraversalTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun composeTheChromeArrangement() {
        rule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                // Drawing order, which is what decides traversal without the modifiers below.
                Box(
                    modifier = Modifier.fillMaxSize().testTag(PAGE).pageTraversal(),
                ) { Text(text = PAGE) }
                Box(
                    modifier = Modifier.align(Alignment.TopCenter).testTag(TOP).topChromeTraversal(),
                ) { Text(text = TOP) }
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).testTag(BOTTOM).bottomChromeTraversal(),
                ) { Text(text = BOTTOM) }
            }
        }
    }

    @Test
    fun theChromeIsReadBeforeAndAfterThePageRatherThanThroughIt() {
        val top = traversalIndexOf(TOP)
        val page = traversalIndexOf(PAGE)
        val bottom = traversalIndexOf(BOTTOM)

        assertEquals(CHROME_TOP_INDEX, top)
        assertEquals(PAGE_INDEX, page)
        assertEquals(CHROME_BOTTOM_INDEX, bottom)
        assertTrue("top chrome must sort before the page", top < page)
        assertTrue("the bottom pill must sort after the page", page < bottom)
    }

    @Test
    fun eachOfTheThreeIsOneBlockRatherThanLooseButtons() {
        // A `traversalIndex` orders a node against its peers only, so without the group the chrome's
        // buttons would be sorted against the page's rows one by one and the indices buy nothing.
        listOf(TOP, PAGE, BOTTOM).forEach { tag ->
            val config = rule.onNodeWithTag(tag).fetchSemanticsNode().config
            val group = config.getOrNull(SemanticsProperties.IsTraversalGroup)
            assertEquals("$tag must declare itself a traversal group", true, group)
        }
    }

    private fun traversalIndexOf(tag: String): Float {
        val config = rule.onNodeWithTag(tag).fetchSemanticsNode().config
        return requireNotNull(config.getOrNull(SemanticsProperties.TraversalIndex)) {
            "$tag declares no traversal index"
        }
    }

    private companion object {
        const val TOP = "top chrome"
        const val PAGE = "the page"
        const val BOTTOM = "bottom pill"
    }
}
