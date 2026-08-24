package dev.jellyboost.data.downloads

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The package layering of `:data:downloads`, enforced by reading the source.
 *
 * This module had two import cycles — root↔`plan`, and root→`work`→`engine`→root — and nothing in the
 * build could see them: Gradle only knows about *module* dependencies, detekt has no package-cycle
 * rule, and a cycle is invisible in the one diff that closes it.
 *
 * It scans `src/main/kotlin` rather than the classpath because an import is exactly the thing being
 * ruled on, and it is erased from the bytecode (Kotlin resolves imports at compile time).
 *
 * ### The layering
 * Lowest first; a package may only import packages **strictly** below it.
 *
 * | layer | package | what it is |
 * |---|---|---|
 * | 0 | `model` | the types the UI renders |
 * | 0 | `plan` | pure functions from a DTO to file names and URLs |
 * | 0 | `storage` | where bytes live on disk |
 * | 1 | `engine` | the transfer machinery: queue, downloader, MKV repair, seeding, sweeping |
 * | 2 | *(root)* | the module's surface — `DownloadRepository`, the refresher, `DownloadApi` |
 * | 2 | `offline` | what the player asks about a completed download |
 * | 3 | `work` | WorkManager scheduling, the worker, the notification |
 * | 4 | `impl` | the implementations behind the root interfaces |
 * | 5 | `di` | the Hilt bindings, which by definition see everything |
 *
 * Adding a package is a deliberate act: [LAYERS] is asserted to name exactly the packages that exist,
 * so a new one fails here until someone has decided where it sits.
 */
class PackageDependencyTest {
    private val graph = PackageGraph.scan(sourceRoot())

    @Test
    fun `the scan actually found the module's sources`() {
        // Without this a broken path would make every other assertion in this class vacuously pass.
        graph.fileCount shouldBeGreaterThan MINIMUM_EXPECTED_FILES
    }

    @Test
    fun `every package in the module has a declared layer`() {
        graph.packages.sorted() shouldContainExactly LAYERS.keys.sorted()
    }

    @Test
    fun `no package imports another at or above its own layer`() {
        val violations =
            graph.edges
                .filter { (from, to) -> LAYERS.getValue(from) <= LAYERS.getValue(to) }
                .map { (from, to) ->
                    "$from (layer ${LAYERS.getValue(from)}) imports $to (layer ${LAYERS.getValue(to)})"
                }

        violations shouldContainExactly emptyList<String>()
    }

    @Test
    fun `the package graph is acyclic`() {
        // Independent of [LAYERS] on purpose: relaxing the table above must not also switch off the
        // check the table exists for.
        firstCycle(graph.edges) shouldBe null
    }

    /** A cycle in the import graph as `a -> b -> a`, or `null` when there is none. */
    private fun firstCycle(edges: Set<Pair<String, String>>): String? {
        val outgoing = edges.groupBy({ it.first }, { it.second })
        val settled = mutableSetOf<String>()
        val path = mutableListOf<String>()

        @Suppress(
            // A depth-first walk: "already on the path" (a cycle), "already settled", the bubbled-up
            // cycle from a child, and "clean" are four genuinely different outcomes.
            "ReturnCount",
        )
        fun walk(node: String): String? {
            val seenAt = path.indexOf(node)
            if (seenAt >= 0) return (path.drop(seenAt) + node).joinToString(" -> ")
            if (node in settled) return null

            path += node
            outgoing[node].orEmpty().forEach { next -> walk(next)?.let { return it } }
            path.removeAt(path.lastIndex)
            settled += node
            return null
        }

        return outgoing.keys.firstNotNullOfOrNull(::walk)
    }

    private companion object {
        /** The name this test uses for the `dev.jellyboost.data.downloads` package itself. */
        const val ROOT = "<root>"

        /** Enough files that a mis-resolved source root cannot pass as a scan. */
        const val MINIMUM_EXPECTED_FILES = 25

        val LAYERS =
            mapOf(
                "model" to 0,
                "plan" to 0,
                "storage" to 0,
                "engine" to 1,
                ROOT to 2,
                "offline" to 2,
                "work" to 3,
                "impl" to 4,
                "di" to 5,
            )

        /**
         * Gradle runs unit tests with the module directory as the working directory, but the
         * two-candidate walk means this also works from the repository root.
         */
        fun sourceRoot(): File {
            val suffix = "src/main/kotlin/dev/jellyboost/data/downloads"
            val candidates = listOf(suffix, "data/downloads/$suffix")
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile

            return generateSequence(start) { it.parentFile }
                .flatMap { directory -> candidates.asSequence().map { File(directory, it) } }
                .firstOrNull { it.isDirectory }
                ?: error("Could not find $suffix from $start")
        }
    }

    /** The package-to-package import edges of one source tree. */
    private class PackageGraph(
        val packages: Set<String>,
        val edges: Set<Pair<String, String>>,
        val fileCount: Int,
    ) {
        companion object {
            private const val PREFIX = "dev.jellyboost.data.downloads"
            private val PACKAGE_LINE = Regex("^package \\Q$PREFIX\\E(?:\\.(\\w+))?\\s*$", RegexOption.MULTILINE)
            private val IMPORT_LINE = Regex("^import \\Q$PREFIX\\E\\.(\\S+)\\s*$", RegexOption.MULTILINE)

            fun scan(root: File): PackageGraph {
                val packages = mutableSetOf<String>()
                val edges = mutableSetOf<Pair<String, String>>()
                var files = 0

                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                    files++
                    val source = file.readText()
                    val owner =
                        PACKAGE_LINE
                            .find(source)
                            ?.groupValues
                            ?.get(1)
                            ?.ifEmpty { ROOT } ?: ROOT
                    packages += owner

                    IMPORT_LINE.findAll(source).forEach { match ->
                        val target = match.groupValues[1].packageOf()
                        if (target != owner) edges += owner to target
                    }
                }

                return PackageGraph(packages, edges, files)
            }

            /**
             * The first segment is a package when it starts lower-case and a top-level declaration when
             * it does not — Kotlin's own convention, and the only signal an import carries.
             */
            private fun String.packageOf(): String {
                val head = substringBefore('.')
                return if (head != this && head.first().isLowerCase()) head else ROOT
            }
        }
    }
}
