package dev.jellyboost.core.database

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
 * Guards the one property that lets every version bump stay a Room `@AutoMigration`: **each schema is purely
 * additive over the last**. Room can only derive the `ALTER TABLE` when nothing was dropped, renamed or
 * retyped and every new `NOT NULL` column brings a SQL default; get it wrong and the failure is a crash on
 * upgrade, for the population a developer's own device does not represent.
 *
 * Reads the **exported schemas** — what Room actually migrates against — not the entity classes.
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

        (after.keys - before.keys) shouldContainExactly setOf("projectedBytes", "sizeIsExact")
    }

    @Test
    fun `v5 to v6 drops no column and changes no type`() {
        val before = columns(5, "downloads")
        val after = columns(6, "downloads")

        // A dropped or retyped column is what `@AutoMigration` cannot derive, and what would leave an
        // existing install unable to open its own queue.
        (before.keys - after.keys).shouldBeEmpty()
        before.forEach { (name, column) -> after.getValue(name) shouldBe column }
    }

    @Test
    fun `projectedBytes is nullable, so an older row simply has no projection`() {
        val column = columns(6, "downloads").getValue("projectedBytes")

        column.affinity shouldBe "INTEGER"
        column.notNull shouldBe false
        // Nullable needs no default: a pre-v6 row reads back as NULL, which is "the ceiling is still the
        // best answer" — what that row always meant.
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

    @Test
    fun `v7 to v8 adds the baked audio column and touches nothing else`() {
        val before = columns(7, "downloads")
        val after = columns(8, "downloads")

        (after.keys - before.keys) shouldContainExactly setOf("bakedAudioStreamIndex")
    }

    @Test
    fun `v7 to v8 drops no column and changes no type`() {
        val before = columns(7, "downloads")
        val after = columns(8, "downloads")

        (before.keys - after.keys).shouldBeEmpty()
        before.forEach { (name, column) -> after.getValue(name) shouldBe column }
    }

    @Test
    fun `bakedAudioStreamIndex is nullable, so an older row simply records no pin`() {
        val column = columns(8, "downloads").getValue("bakedAudioStreamIndex")

        column.affinity shouldBe "INTEGER"
        column.notNull shouldBe false
        // `NULL` is the honest reading of every row written before v8: nothing recorded which track it got,
        // so offline playback keeps its old `defaultAudioStreamIndex` assumption for exactly those.
        column.defaultValue shouldBe null
    }

    @Test
    fun `v8 adds no table and removes none`() {
        tables(8) shouldContainExactly tables(7)
    }

    @Test
    fun `v8 to v9 changes no column at all, on any table`() {
        // v9 is an *index-only* bump, which Room can still derive — but only because nothing about the
        // columns moved.
        tables(9) shouldContainExactly tables(8)
        tables(9).forEach { table -> columns(9, table) shouldBe columns(8, table) }
    }

    @Test
    fun `v9 replaces the items indices the query plans never used`() {
        val before = indices(8, "items")
        val after = indices(9, "items")

        (after - before.keys) shouldBe
            mapOf(
                "index_items_source_type" to listOf("source", "type"),
                "index_items_source_cachedAt" to listOf("source", "cachedAt"),
            )
        // Three single-column indices out: `sortName` was BINARY where both consumers sort `COLLATE NOCASE`
        // (never chosen at all), and `source`/`cachedAt` are subsumed by the composites — EXPLAIN QUERY PLAN
        // is byte-identical without them.
        (before.keys - after.keys) shouldContainExactly
            setOf("index_items_source", "index_items_cachedAt", "index_items_sortName")
    }

    @Test
    fun `v9 gives the sibling-size lookups the composite they filter on`() {
        val before = indices(8, "downloads")
        val after = indices(9, "downloads")

        (after - before.keys) shouldBe
            mapOf("index_downloads_seriesName_quality" to listOf("seriesName", "quality"))
        (before.keys - after.keys).shouldBeEmpty()
    }

    @Test
    fun `v10 to v11 adds the grouping columns and touches nothing else`() {
        val before = columns(10, "downloads")
        val after = columns(11, "downloads")

        (after.keys - before.keys) shouldContainExactly setOf("itemType", "albumName", "groupId")
    }

    @Test
    fun `v10 to v11 drops no column and changes no type`() {
        val before = columns(10, "downloads")
        val after = columns(11, "downloads")

        (before.keys - after.keys).shouldBeEmpty()
        before.forEach { (name, column) -> after.getValue(name) shouldBe column }
    }

    @Test
    fun `the grouping columns are nullable with no default, which is what keeps the bump automatic`() {
        val after = columns(11, "downloads")

        // Nullable with no default: a row that predates these columns reads back as NULL, which is what
        // sends the read path to the cached item for its kind and heading.
        listOf("itemType", "albumName", "groupId").forEach { name ->
            val column = after.getValue(name)
            column.affinity shouldBe "TEXT"
            column.notNull shouldBe false
            column.defaultValue shouldBe null
        }
    }

    @Test
    fun `v11 adds no table and removes none`() {
        tables(11) shouldContainExactly tables(10)
    }

    @Test
    fun `v11 adds no index, since the grouping happens in Kotlin`() {
        indices(11, "downloads") shouldBe indices(10, "downloads")
    }

    @Test
    fun `v11 to v12 adds the artist column and touches nothing else`() {
        val before = columns(11, "downloads")
        val after = columns(12, "downloads")

        (after.keys - before.keys) shouldContainExactly setOf("artistName")
    }

    @Test
    fun `v11 to v12 drops no column and changes no type`() {
        val before = columns(11, "downloads")
        val after = columns(12, "downloads")

        (before.keys - after.keys).shouldBeEmpty()
        before.forEach { (name, column) -> after.getValue(name) shouldBe column }
    }

    @Test
    fun `artistName is nullable with no default, which is what keeps the bump automatic`() {
        val column = columns(12, "downloads").getValue("artistName")

        column.affinity shouldBe "TEXT"
        column.notNull shouldBe false
        // NULL on every row written before v12, which is what sends the read path to the cached item.
        column.defaultValue shouldBe null
    }

    @Test
    fun `v12 adds no table, removes none, and adds no index`() {
        tables(12) shouldContainExactly tables(11)
        indices(12, "downloads") shouldBe indices(11, "downloads")
    }

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

    /** name → the columns, in index order. */
    private fun indices(
        version: Int,
        table: String,
    ): Map<String, List<String>> =
        schema(version)["database"]!!
            .jsonObject["entities"]!!
            .jsonArray
            .first { it.jsonObject["tableName"]!!.jsonPrimitive.content == table }
            .jsonObject["indices"]
            ?.jsonArray
            .orEmpty()
            .associate { element ->
                val index = element.jsonObject
                index["name"]!!.jsonPrimitive.content to
                    index["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
            }

    private companion object {
        /** Where the Room convention plugin exports them. */
        const val SCHEMA_DIR = "schemas/dev.jellyboost.core.database.JellyfinDatabase"
    }
}
