package dev.jellyboost.data.downloads.storage

import dev.jellyboost.core.datastore.AppPreferences
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [StorageLocationManager] — the resolution rule behind a configurable download path.
 *
 * The rule is one sentence ("the chosen volume if it is mounted, the primary volume otherwise") and
 * every failure mode of it is a user losing files: writing to the wrong card, writing nowhere, or
 * an app that will not download at all because the card it was told about is on a desk somewhere.
 * Temporary directories stand in for the volumes, which is the whole reason `StorageVolumeProvider`
 * is an interface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageLocationManagerTest {
    @TempDir
    lateinit var tempDir: File

    private val preferences = mockk<AppPreferences>(relaxUnitFun = true)

    private fun volume(
        id: String,
        isPrimary: Boolean = false,
    ): DownloadVolume =
        DownloadVolume(
            id = id,
            isPrimary = isPrimary,
            isRemovable = !isPrimary,
            description = if (isPrimary) "Internal shared storage" else "SD card",
            directory = File(tempDir, id).also { it.mkdirs() },
        )

    private fun manager(
        volumes: List<DownloadVolume>,
        stored: String? = null,
        // `Unconfined` so the seed the constructor launches has landed by the time it returns —
        // the app scope's behaviour, without a scheduler to advance. `seedNever()` below is the
        // test for the window before it lands.
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): StorageLocationManager {
        every { preferences.downloadStorageVolumeId } returns MutableStateFlow(stored)
        return StorageLocationManager(
            volumeProvider =
                object : StorageVolumeProvider {
                    override fun volumes(): List<DownloadVolume> = volumes
                },
            preferences = preferences,
            appScope = scope,
        )
    }

    // Lazy because `@TempDir` is injected after construction, and these need the directory.
    private val primary by lazy { volume(DownloadVolume.PRIMARY_ID, isPrimary = true) }
    private val card by lazy { volume("1A2B-3C4D") }

    // ---- resolution ------------------------------------------------------------------------------

    @Test
    fun `with no stored choice downloads go to the primary volume`() {
        val selection = manager(listOf(primary, card)).resolve(selectedVolumeId = null)

        selection.active shouldBe primary
        selection.selectionMissing shouldBe false
        selection.volumes shouldHaveSize 2
    }

    @Test
    fun `the stored volume is used when it is mounted`() {
        val selection = manager(listOf(primary, card), stored = card.id).resolve(card.id)

        selection.active shouldBe card
        selection.selectionMissing shouldBe false
    }

    @Test
    fun `a stored volume that is gone falls back to the primary one and says so`() {
        val selection = manager(listOf(primary), stored = card.id).resolve(card.id)

        selection.active shouldBe primary
        // The fallback is the whole point of the flag: writing to internal storage silently is how
        // a user ends up hunting for downloads that were never on the card.
        selection.selectionMissing shouldBe true
    }

    @Test
    fun `no mounted volume at all resolves to nothing rather than throwing`() {
        val manager = manager(volumes = emptyList())

        manager.resolve(selectedVolumeId = null).active.shouldBeNull()
        manager.activeRoot().shouldBeNull()
    }

    // ---- the root the pipeline writes to ---------------------------------------------------------

    @Test
    fun `the root is a downloads directory inside the volume's app-specific directory`() {
        val root = manager(listOf(primary, card), stored = card.id).activeRoot()

        root shouldBe File(card.directory, "downloads")
    }

    @Test
    fun `the primary root is exactly where downloads were written before the picker existed`() {
        val root = manager(listOf(primary, card)).activeRoot()

        root shouldBe File(primary.directory, "downloads")
    }

    // ---- switching -------------------------------------------------------------------------------

    @Test
    fun `select persists the choice`() =
        runTest {
            val manager = manager(listOf(primary, card))

            manager.select(card.id)

            coVerify { preferences.setDownloadStorageVolumeId(card.id) }
        }

    @Test
    fun `a selection takes effect on the very next write, not on the next process`() =
        runTest {
            // A preference flow that never reports the change: only the manager's own cache can
            // make the new root visible here, which is exactly the guarantee the download worker
            // relies on when it resolves paths straight after the user switched.
            every { preferences.downloadStorageVolumeId } returns flowOf(null)
            val manager =
                StorageLocationManager(
                    volumeProvider =
                        object : StorageVolumeProvider {
                            override fun volumes(): List<DownloadVolume> = listOf(primary, card)
                        },
                    preferences = preferences,
                    appScope = CoroutineScope(Dispatchers.Unconfined),
                )
            manager.activeRoot() shouldBe File(primary.directory, "downloads")

            manager.select(card.id)

            manager.activeRoot() shouldBe File(card.directory, "downloads")
        }

    // ---- the seeding window ---------------------------------------------------------------------

    @Test
    fun `a read before the stored choice has been loaded resolves the primary volume`() =
        runTest {
            // Filling the cache with a `runBlocking` under the non-suspending
            // `DownloadStorage.rootPath` would charge a DataStore read to whatever thread got there
            // first. It is seeded from the app scope instead, and this is the window that opens:
            // `null` is not a failure but the documented default — "no volume chosen" — and it
            // resolves to the path every download before the picker existed was written to.
            // A scope that has not run yet: the seed is queued, exactly as it is in the moment
            // between the graph being built and the app scope getting a turn.
            val unstarted = TestScope()
            val manager = manager(listOf(primary, card), stored = card.id, scope = unstarted)

            manager.activeRoot() shouldBe File(primary.directory, "downloads")

            // And once the seed lands, the stored choice is in force with nothing else happening.
            unstarted.advanceUntilIdle()
            manager.activeRoot() shouldBe File(card.directory, "downloads")
        }
}
