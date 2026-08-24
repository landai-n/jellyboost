package dev.jellyboost.core.ui.component

import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for what [JellyfinTextField]'s three value types mean to a screen reader.
 *
 * The field's accessibility guarantees would otherwise rest on four pairs of parameters a caller
 * had to keep in agreement by hand, with call-site discipline the only thing holding them
 * together. Collapsing them into [FieldLabel], [FieldState]
 * and [FieldContent] moves that agreement into the type system — and moves the mapping into plain
 * functions, which is what lets a JVM test hold each variant's semantics still without composing
 * anything. The composed half (the node really carrying these) is `ChipAndFieldA11yTest`.
 */
class FieldSemanticsTest {
    @Nested
    inner class Labels {
        @Test
        @DisplayName("an eyebrow label draws its name uppercased and speaks it as written")
        fun eyebrowUppercasesOnlyTheCaption() {
            // "SERVER ADDRESS" handed to TTS is spelled out letter by letter, which is not a label.
            val label = FieldLabel.eyebrow("Server address")

            label.text shouldBe "Server address"
            label.caption shouldBe "SERVER ADDRESS"
        }

        @Test
        @DisplayName("a label with no caption is spoken but never drawn")
        fun plainLabelDrawsNothing() {
            // A placeholder vanishes the moment there is a value, so the node carries the name.
            val label = FieldLabel(text = "Search")

            label.text shouldBe "Search"
            label.caption.shouldBeNull()
        }
    }

    @Nested
    inner class States {
        @Test
        @DisplayName("Editable: takes keystrokes, no error to announce")
        fun editable() {
            FieldState.Editable.isReadOnly shouldBe false
            FieldState.Editable.isError shouldBe false
            FieldState.Editable.errorMessage.shouldBeNull()
        }

        @Test
        @DisplayName("InFlight: read-only rather than disabled, and not an error")
        fun inFlight() {
            // `enabled = false` destroys the node a TalkBack user is standing on, and waiting is
            // not failing.
            FieldState.InFlight.isReadOnly shouldBe true
            FieldState.InFlight.isError shouldBe false
            FieldState.InFlight.errorMessage.shouldBeNull()
        }

        @Test
        @DisplayName("Error: carries the sentence it announces, and still takes keystrokes")
        fun error() {
            val state = FieldState.Error(FAILURE)

            state.isError shouldBe true
            // `isError = true` with no message announces "invalid" and nothing else.
            state.errorMessage shouldBe FAILURE
            // A rejected value is a value the user must be able to correct.
            state.isReadOnly shouldBe false
        }
    }

    @Nested
    inner class Contents {
        @Test
        @DisplayName("Plain: never a secret, never masked, autofill only if asked")
        fun plain() {
            val content = FieldContent.Plain()

            content.isSecret shouldBe false
            content.visualTransformation shouldBe VisualTransformation.None
            content.autofillContentType.shouldBeNull()
        }

        @Test
        @DisplayName("Plain: the autofill hint it was given, and nothing else changes")
        fun plainWithAutofill() {
            val content = FieldContent.Plain(autofill = ContentType.Username)

            content.autofillContentType shouldBe ContentType.Username
            content.isSecret shouldBe false
        }

        @Test
        @DisplayName("Password: a secret node, masked characters, password autofill")
        fun password() {
            val content = FieldContent.Password()

            content.isSecret shouldBe true
            content.visualTransformation.shouldBeInstanceOf<PasswordVisualTransformation>()
            content.autofillContentType shouldBe ContentType.Password
        }

        @Test
        @DisplayName("Password revealed: the eye shows the characters, the node stays a secret")
        fun revealedPassword() {
            val content = FieldContent.Password(revealed = true)

            content.visualTransformation shouldBe VisualTransformation.None
            content.isSecret shouldBe true
            content.autofillContentType shouldBe ContentType.Password
        }
    }

    private companion object {
        const val FAILURE = "That server did not answer."
    }
}
