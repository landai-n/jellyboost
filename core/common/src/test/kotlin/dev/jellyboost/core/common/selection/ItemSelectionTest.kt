package dev.jellyboost.core.common.selection

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Unit tests for the shared batch-selection model (docs/features/batch-selection.md). */
class ItemSelectionTest {
    @Test
    fun `an empty selection is not in selection mode`() {
        ItemSelection().isActive shouldBe false
        ItemSelection().count shouldBe 0
    }

    @Test
    fun `toggling selects, and toggling again deselects`() {
        val once = ItemSelection().toggled("a")
        once.ids shouldContainExactly setOf("a")
        once.isActive shouldBe true

        // Mode is derived from emptiness, so deselecting the last item ends the mode by itself.
        once.toggled("a").isActive shouldBe false
    }

    @Test
    fun `selection order is the order things were selected`() {
        val selection = ItemSelection().toggled("b").toggled("a").toggled("c")

        // The batch runs in this order, so a partial failure is reported against a predictable run.
        selection.ids.toList() shouldContainExactly listOf("b", "a", "c")
    }

    @Test
    fun `select all keeps what was already selected and adds the rest, in list order`() {
        val selection = ItemSelection().toggled("c").selecting(listOf("a", "b", "c"))

        selection.ids.toList() shouldContainExactly listOf("c", "a", "b")
    }

    @Test
    fun `retaining drops ids that are no longer in the list`() {
        val selection = ItemSelection().selecting(listOf("a", "b", "c"))

        selection.retaining(listOf("a", "c")).ids shouldContainExactly setOf("a", "c")
    }

    @Test
    fun `retaining an unchanged list returns the very same value`() {
        val selection = ItemSelection().selecting(listOf("a", "b"))

        // Identity, not just equality: a reload that changed nothing must not make the flow emit.
        (selection.retaining(listOf("a", "b", "z")) === selection) shouldBe true
    }

    @Test
    fun `clearing empties the selection`() {
        ItemSelection().selecting(listOf("a", "b")).cleared() shouldBe ItemSelection()
    }

    @Test
    fun `a batch runs every target and counts both outcomes`() =
        runTest {
            val seen = mutableListOf<String>()

            val outcome =
                runBatch(listOf("a", "b", "c")) { id ->
                    seen += id
                    if (id == "b") AppResult.Failure(AppError.Network()) else AppResult.Success(Unit)
                }

            // Never short-circuits: "c" is attempted even though "b" failed.
            seen shouldContainExactly listOf("a", "b", "c")
            outcome shouldBe BatchOutcome(done = 2, failed = 1)
        }

    @Test
    fun `a batch carries the caller's skipped count through`() =
        runTest {
            val outcome = runBatch(listOf("a"), skipped = 3) { AppResult.Success(Unit) }

            outcome shouldBe BatchOutcome(done = 1, failed = 0, skipped = 3)
        }

    @Test
    fun `a batch with nothing to do reports nothing done`() =
        runTest {
            runBatch(emptyList(), skipped = 2) { AppResult.Success(Unit) } shouldBe
                BatchOutcome(done = 0, failed = 0, skipped = 2)
        }
}
