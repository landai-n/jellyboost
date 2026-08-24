package dev.jellyboost.core.database.converters

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class InstantConverterTest {
    private val converter = InstantConverter()

    @Test
    fun `round-trips an instant through epoch milliseconds`() {
        val instant = Instant.parse("2026-07-28T10:15:30Z")

        converter.toInstant(converter.fromInstant(instant)) shouldBe instant
    }

    @Test
    fun `stores epoch milliseconds, not text`() {
        converter.fromInstant(Instant.ofEpochMilli(1_700_000_000_500L)) shouldBe 1_700_000_000_500L
    }

    @Test
    fun `maps null in both directions`() {
        converter.fromInstant(null).shouldBeNull()
        converter.toInstant(null).shouldBeNull()
    }

    @Test
    fun `stored values order the same way the instants do`() {
        // The property the pending-sync guard relies on: `updatedAt <= :syncedAt` has to mean the same thing
        // in SQL as in Kotlin. ISO-8601 text would fail this case, because '.' sorts before 'Z'.
        val earlier = Instant.parse("2026-07-28T10:15:30.500Z")
        val later = Instant.parse("2026-07-28T10:15:31Z")

        val earlierStored = converter.fromInstant(earlier)!!
        val laterStored = converter.fromInstant(later)!!

        (earlierStored < laterStored) shouldBe true
        (earlier < later) shouldBe true
    }
}
