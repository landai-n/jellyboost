package dev.jellyboost.core.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellyboost.core.ui.a11y.AccessibilityChecks
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Where the three chips' split — a screen reader hears three different things — is a fact. */
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
        // No `selected`: it can never announce "not selected" at a user who cannot change that.
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
                    label = FieldLabel.eyebrow(LABEL),
                )
            }
        }

        val field = rule.onNodeWithText(ADDRESS).fetchSemanticsNode()
        assertEquals(LABEL, field.config[SemanticsProperties.ContentDescription].single())
        // Without the caption's `clearAndSetSemantics`, "SERVER ADDRESS" is spelled out letter by
        // letter immediately before the field says the same words.
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
                    label = FieldLabel(text = LABEL),
                    state = FieldState.InFlight,
                )
            }
        }

        val field = rule.onNodeWithText(ADDRESS)
        // Still a named node, which `enabled = false` would have destroyed mid-form.
        assertEquals(LABEL, field.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())

        // Behaviour, not the absence of a `SetText` action: whether a read-only field publishes one
        // is Compose-version dependent, so the throw is a pass too.
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
                    label = FieldLabel(text = LABEL),
                    state = FieldState.Error(FAILURE),
                )
            }
        }

        val field = rule.onNodeWithText(ADDRESS).fetchSemanticsNode()
        // `error(…)` on the field's own node is what makes a reader say *what* went wrong.
        assertEquals(FAILURE, field.config[SemanticsProperties.Error])
        // …and the name is still the label: the failure adds to the field, it does not rename it.
        assertEquals(LABEL, field.config[SemanticsProperties.ContentDescription].single())
    }

    @Test
    fun aFieldWithATrailingButtonStandsExactlyAsTallAsOneWithout() {
        rule.setContent {
            JellyfinTheme {
                Column {
                    JellyfinTextField(
                        value = ADDRESS,
                        onValueChange = {},
                        label = FieldLabel(text = LABEL),
                    )
                    JellyfinTextField(
                        value = ADDRESS,
                        onValueChange = {},
                        label = FieldLabel(text = SECRET_LABEL),
                        content = FieldContent.Password(),
                        trailingIcon = {
                            IconButton(onClick = {}) {
                                Icon(imageVector = Icons.Filled.Visibility, contentDescription = REVEAL)
                            }
                        },
                    )
                }
            }
        }

        val plain = rule.onNodeWithContentDescription(LABEL).getUnclippedBoundsInRoot()
        val withButton = rule.onNodeWithContentDescription(SECRET_LABEL).getUnclippedBoundsInRoot()
        // The 48dp target lays out inside the well; letting it measure the row instead made the
        // password field visibly taller than the username field above it.
        assertEquals(plain.height, withButton.height)
        // The button's own node is the target — it carries the click and the semantics — so a
        // 48dp frame drawn around a 40dp `IconButton` state layer measures 40dp to a11y tooling.
        val reveal = rule.onNodeWithContentDescription(REVEAL)
        reveal.assertHeightIsAtLeast(Dimens.MinTouchTarget)
        reveal.assertWidthIsAtLeast(Dimens.MinTouchTarget)
    }

    @Test
    fun aLeadingIconDoesNotChangeWhatTheTrailingButtonMeasures() {
        rule.setContent {
            JellyfinTheme {
                Column {
                    JellyfinTextField(
                        value = ADDRESS,
                        onValueChange = {},
                        label = FieldLabel(text = LABEL),
                    )
                    JellyfinTextField(
                        value = ADDRESS,
                        onValueChange = {},
                        label = FieldLabel(text = SEARCH_LABEL),
                        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = {}) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = CLEAR)
                            }
                        },
                    )
                }
            }
        }

        val plain = rule.onNodeWithContentDescription(LABEL).getUnclippedBoundsInRoot()
        val search = rule.onNodeWithContentDescription(SEARCH_LABEL).getUnclippedBoundsInRoot()
        assertEquals(plain.height, search.height)
        val clear = rule.onNodeWithContentDescription(CLEAR)
        clear.assertHeightIsAtLeast(Dimens.MinTouchTarget)
        clear.assertWidthIsAtLeast(Dimens.MinTouchTarget)
    }

    private companion object {
        const val FILTER = "Unwatched"
        const val SHEET = "Filters"
        const val GENRE = "Sci-Fi"
        const val LABEL = "Server address"
        const val SECRET_LABEL = "Password"
        const val REVEAL = "Show password"
        const val SEARCH_LABEL = "Search"
        const val CLEAR = "Clear search"
        const val ADDRESS = "http://example.invalid:8096"
        const val FAILURE = "That server did not answer."
    }
}
