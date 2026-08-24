package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.model.Person
import dev.jellyboost.core.common.model.PersonKind
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CastRailTest {
    @Test
    fun `actors and guest stars come before crew`() {
        val people =
            listOf(
                person("1", PersonKind.DIRECTOR),
                person("2", PersonKind.ACTOR),
                person("3", PersonKind.WRITER),
                person("4", PersonKind.GUEST_STAR),
            )

        castMembers(people).map { it.id } shouldContainExactly listOf("2", "4", "1", "3")
    }

    @Test
    fun `billing order is stable, so the server's own ordering survives within a kind`() {
        val people = (1..5).map { person(it.toString(), PersonKind.ACTOR) }

        castMembers(people).map { it.id } shouldContainExactly listOf("1", "2", "3", "4", "5")
    }

    @Test
    fun `the rail stops at the cap`() {
        val people = (1..30).map { person(it.toString(), PersonKind.ACTOR) }

        castMembers(people).size shouldBe CAST_LIMIT
    }

    @Test
    fun `a person credited twice appears once`() {
        val people =
            listOf(
                person("1", PersonKind.ACTOR),
                person("1", PersonKind.WRITER),
                person("2", PersonKind.ACTOR),
            )

        castMembers(people).map { it.id } shouldContainExactly listOf("1", "2")
    }

    @Test
    fun `a crew-only credit list still fills the rail`() {
        val people = listOf(person("1", PersonKind.DIRECTOR), person("2", PersonKind.PRODUCER))

        castMembers(people).map { it.id } shouldContainExactly listOf("1", "2")
    }

    @Test
    fun `nobody credited means no rail`() {
        castMembers(emptyList()) shouldBe emptyList()
    }

    private fun person(
        id: String,
        kind: PersonKind,
    ) = Person(id = id, name = "Person $id", kind = kind)
}
