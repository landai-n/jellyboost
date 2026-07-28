package dev.jellyfinnative.core.database.converters

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** Unit tests for [UuidConverter]'s Room type-conversion pair. */
class UuidConverterTest {
    private val converter = UuidConverter()

    @Test
    fun `fromUuid converts a UUID to its canonical string form`() {
        val uuid = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")

        converter.fromUuid(uuid) shouldBe "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    }

    @Test
    fun `fromUuid returns null for a null input`() {
        converter.fromUuid(null).shouldBeNull()
    }

    @Test
    fun `toUuid parses a stored string back into a UUID`() {
        converter.toUuid("f47ac10b-58cc-4372-a567-0e02b2c3d479") shouldBe
            UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    }

    @Test
    fun `toUuid returns null for a null input`() {
        converter.toUuid(null).shouldBeNull()
    }

    @Test
    fun `round trip through fromUuid and toUuid preserves the original UUID`() {
        val uuid = UUID.randomUUID()

        converter.toUuid(converter.fromUuid(uuid)) shouldBe uuid
    }
}
