package dev.jellyfinnative.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the one property that lets every version bump stay a Room `@AutoMigration`: **each schema
 * is purely additive over the last**.
 *
 * `@AutoMigration` is not free of risk, it is free of *hand-written SQL* — Room still has to be able
 * to derive the `ALTER TABLE` itself, and it can only do that when nothing was dropped, renamed or
 * retyped, and when every new `NOT NULL` column brings a SQL default. Get that wrong and the failure
 * is a crash on upgrade for users who have an install, which is precisely the population a
 * developer's own device does not represent.
 *
 * So this reads the **exported schemas** — the artefacts Room actually migrates against, written to
 * `core/database/schemas/` on every build — rather than the entity classes. Comparing v5 to v6 the
 * way Room does is what makes this a migration test and not a restatement of `DownloadEntity`.
 */
class SchemaMigrationTest {
    @Test
    fun `the exported schema is the version the constants declare`() {
        val schema = schema(DatabaseConstants.DATABASE_VERSION)

        schema["database"]!!.jsonObject["version"]!!.jsonPrimitive.content shouldBe
            DatabaseConstants.DATABASE_VERSION.toString()
    }

    @Test
    fun `v5 to v6 adds the projection columns and touches nothing else`() {
        val before = columns(5, "downloads")
        val after = columns(6, "downloads")

        // Exactly the two columns the live size projection needs (docs/features/download-quality.md).
        (after.keys - before.keys) shouldContainExactly setOf("projectedBytes", "sizeIsExact")
    }

    @Test
    fun `v5 to v6 drops no column and changes no type`() {
        val before = columns(5, "downloads")
        val after = columns(6, "downloads")

        // A dropped or retyped column is what `@AutoMigration` cannot derive, and what would leave
        // an existing install unable to open its own queue.
        (before.keys - after.keys).shouldBeEmpty()
        before.forEach { (name, column) -> after.getValue(name) shouldBe column }
    }

    @Test
    fun `projectedBytes is nullable, so an older row simply has no projection`() {
        val column = columns(6, "downloads").getValue("projectedBytes")

        column.affinity shouldBe "INTEGER"
        column.notNull shouldBe false
        // Nullable needs no default: every row a pre-v6 build wrote reads back as NULL, which is
        // exactly "the ceiling is still the best answer" — what that row always meant.
        column.defaultValue shouldBe null
    }

    @Test
    fun `sizeIsExact is NOT NULL with a SQL default, which is what keeps the bump automatic`() {
        val column = columns(6, "downloads").getValue("sizeIsExact")

        column.affinity shouldBe "INTEGER"
        column.notNull shouldBe true
        // `0` = "this size is only a ceiling", the honest reading of every row written before v6.
        column.defaultValue shouldBe "0"
    }

    @Test
    fun `v6 adds no table and removes none`() {
        tables(6) shouldContainExactly tables(5)
    }

    // ---- reading the exported schemas -------------------------------------------------------------

    /** One column as Room recorded it — the fields an auto-migration is derived from. */
    private data class Column(
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )

    private fun schema(version: Int): JsonObject {
        val file = File(SCHEMA_DIR, "$version.json")
        check(file.exists()) { "No exported schema at ${file.absolutePath}; run a build first." }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun tables(version: Int): Set<String> =
        schema(version)["database"]!!
            .jsonObject["entities"]!!
            .jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
            .toSet()

    private fun columns(
        version: Int,
        table: String,
    ): Map<String, Column> =
        schema(version)["database"]!!
            .jsonObject["entities"]!!
            .jsonArray
            .first { it.jsonObject["tableName"]!!.jsonPrimitive.content == table }
            .jsonObject["fields"]!!
            .jsonArray
            .associate { element ->
                val field = element.jsonObject
                field["columnName"]!!.jsonPrimitive.content to
                    Column(
                        affinity = field["affinity"]!!.jsonPrimitive.content,
                        notNull = field["notNull"]?.jsonPrimitive?.content == "true",
                        defaultValue = field["defaultValue"]?.jsonPrimitive?.content,
                    )
            }

    private companion object {
        /** Where the Room convention plugin exports them (`AndroidRoomConventionPlugin`). */
        const val SCHEMA_DIR = "schemas/dev.jellyfinnative.core.database.JellyfinDatabase"
    }
}
