package dev.jellyboost.data.downloads.storage

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileDownloadStorageTest {
    @TempDir
    lateinit var root: File

    private val locations = mockk<StorageLocationManager>()

    @Test
    fun `only directories are listed, and by name`() {
        File(root, "Arrival (2016)").mkdirs()
        File(root, "Westworld - S01E02 - Chestnut").mkdirs()
        // A stray file at the root is not an item and cannot be swept as one.
        File(root, "notes.txt").writeText("x")
        every { locations.activeRoot() } returns root

        storage().itemDirectoryNames() shouldContainExactlyInAnyOrder
            listOf("Arrival (2016)", "Westworld - S01E02 - Chestnut")
    }

    @Test
    fun `an unmounted volume lists nothing rather than failing`() {
        // The sweep compares this against the download rows, so "I cannot read the root" has to read
        // as "found nothing", never as "everything on disk is an orphan".
        every { locations.activeRoot() } returns null

        storage().itemDirectoryNames().shouldBeEmpty()
    }

    @Test
    fun `a root that has to be created first lists nothing`() {
        // A fresh install, or the first drain after a volume switch: the root is made on demand and is empty.
        every { locations.activeRoot() } returns File(root, "downloads")

        storage().itemDirectoryNames().shouldBeEmpty()
    }

    private fun storage() = FileDownloadStorage(locations)
}
