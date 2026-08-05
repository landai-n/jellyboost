package dev.jellyboost.core.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.ui.a11y.AccessibilityChecks
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three chips and the text field — the two components the audit's waves 2 and 5 reshaped most.
 *
 * The chips are one component split into three because a screen reader hears three different
 * things (DECISIONS.md, "an inert chip is its own component" and "a chip that opens a sheet is a
 * button"); this is where that split is a fact rather than a comment. The field's label semantics
 * are CR-2, the highest-traffic finding in the audit: every field in the app used to announce its
 * value and the words "edit box".
 */
@RunWith(AndroidJUnit4::class)
class ChipAndFieldA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val checks by lazy { AccessibilityChecks(rule) }

    @Before
    fun enableAccessibilityChecks() {
        checks.install()
    }

    @Test
    fun aFilterChipIsSelectableAndSaysWhichWayItIsSet() {
        rule.setContent {
            JellyfinTheme {
                PillChip(text = FILTER, selected = true, onClick = {})
            }
        }

        val node = rule.onNodeWithText(FILTER).fetchSemanticsNode()
        assertEquals(true, node.config[SemanticsProperties.Selected])
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        checks.assertClean()
    }

    @Test
    fun anActionChipIsAButtonWithNoStateToBeIn() {
        rule.setContent {
            JellyfinTheme {
                ActionPillChip(text = SHEET, onClick = {})
            }
        }

        val node = rule.onNodeWithText(SHEET).fetchSemanticsNode()
        // The point of the component: no `selected`, so it can never announce "not selected" at a
        // user who has no way to change that.
        assertNull(node.config.getOrNull(SemanticsProperties.Selected))
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        checks.assertClean()
    }

    @Test
    fun anInfoChipIsNotAControlAtAll() {
        rule.setContent {
            JellyfinTheme {
                InfoPillChip(text = GENRE)
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
        val config = rule.onNodeWithText(GENRE).fetchSemanticsNode().config
        assertNull(config.getOrNull(SemanticsProperties.Role))
    }

    @Test
    fun theFieldCarriesItsLabelAsItsNameAndItsCaptionSaysNothing() {
        rule.setContent {
            JellyfinTheme {
                JellyfinTextField(
                    value = ADDRESS,
                    onValueChange = {},
                    label = { Text(text = LABEL.uppercase()) },
                    labelText = LABEL,
                )
            }
        }

        val field = rule.onNodeWithText(ADDRESS).fetchSemanticsNode()
        assertEquals(LABEL, field.config[SemanticsProperties.ContentDescription].single())
        // The drawn caption is `clearAndSetSemantics`-ed: "SERVER ADDRESS" spelled out letter by
        // letter, immediately before the field says the same words, is what this replaced.
        rule.onAllNodesWithText(LABEL.uppercase()).assertCountEquals(0)
    }

    @Test
    fun aReadOnlyFieldKeepsItsNodeAndRefusesTheKeystroke() {
        var typed = ADDRESS
        rule.setContent {
            JellyfinTheme {
                JellyfinTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    readOnly = true,
                    labelText = LABEL,
                )
            }
        }

        val field = rule.onNodeWithText(ADDRESS)
        // Still a named node — which `enabled = false` would have destroyed, dropping a TalkBack
        // user to the top of the form at the moment they pressed Connect (audit F17).
        assertEquals(LABEL, field.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())

        // Asserted as behaviour rather than as the absence of a `SetText` action: a read-only field
        // may or may not publish one depending on the Compose version, and what this test is about
        // is that nothing lands in the field either way. `runCatching` therefore swallows the
        // "action not defined" a version without the action would throw — a *pass* for the same
        // reason the assertion below is.
        runCatching { field.performTextInput("nope") }
        rule.waitForIdle()
        assertEquals(ADDRESS, typed)
    }

    @Test
    fun aFieldInErrorSaysWhatIsWrongRatherThanThatSomethingIs() {
        rule.setContent {
            JellyfinTheme {
                JellyfinTextField(
                    value = ADDRESS,
                    onValueChange = {},
                    isError = true,
                    labelText = LABEL,
                    errorMessage = FAILURE,
                )
            }
        }

        val field = rule.onNodeWithText(ADDRESS).fetchSemanticsNode()
        // `error(…)` on the field's own node, not a sentence floating below it: it is what makes a
        // screen reader say *what* went wrong instead of only that something did (audit CR-2).
        assertEquals(FAILURE, field.config[SemanticsProperties.Error])
        // …and the name is still the label, so the failure is added to the field rather than
        // replacing what the field is called.
        assertEquals(LABEL, field.config[SemanticsProperties.ContentDescription].single())
    }

    private companion object {
        const val FILTER = "Unwatched"
        const val SHEET = "Filters"
        const val GENRE = "Sci-Fi"
        const val LABEL = "Server address"
        const val ADDRESS = "http://example.invalid:8096"
        const val FAILURE = "That server did not answer."
    }
}
