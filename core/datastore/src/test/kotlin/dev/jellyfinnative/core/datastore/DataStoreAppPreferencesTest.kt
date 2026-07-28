package dev.jellyfinnative.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [DataStoreAppPreferences] against a real DataStore backed by a temporary file —
 * the round trip, not a mock, is the thing worth pinning: the force-offline flag is what makes the
 * app ignore a working network, so a setting that silently fails to persist would be invisible
 * until a user complained.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAppPreferencesTest {
    @TempDir
    lateinit var tempDir: File

    private fun dataStore(scope: TestScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(scope.testScheduler)),
            produceFile = { File(tempDir, "app_preferences.preferences_pb") },
        )

    @Test
    fun `force offline defaults to off`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.forceOffline.first() shouldBe false
        }

    @Test
    fun `force offline survives a round trip through storage`() =
        runTest {
            val store = dataStore(this)

            DataStoreAppPreferences(store).setForceOffline(true)

            // A second instance over the same file: this is what an app restart looks like.
            DataStoreAppPreferences(store).forceOffline.first() shouldBe true
        }

    @Test
    fun `turning force offline back off is persisted too`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.setForceOffline(true)
            preferences.setForceOffline(false)

            preferences.forceOffline.first() shouldBe false
        }

    @Test
    fun `emits every change to observers`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.forceOffline.test {
                awaitItem() shouldBe false

                preferences.setForceOffline(true)
                awaitItem() shouldBe true

                preferences.setForceOffline(false)
                awaitItem() shouldBe false

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- M7: Wi-Fi-only downloads ----------------------------------------------------------------

    @Test
    fun `Wi-Fi-only downloads default to on`() =
        runTest {
            // The one preference in this file that does not default to `false`: a multi-gigabyte
            // film pulled over a metered connection is a mistake the user cannot undo.
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.downloadOverWifiOnly.first() shouldBe true
        }

    @Test
    fun `turning Wi-Fi-only off survives a round trip through storage`() =
        runTest {
            val store = dataStore(this)

            DataStoreAppPreferences(store).setDownloadOverWifiOnly(false)

            DataStoreAppPreferences(store).downloadOverWifiOnly.first() shouldBe false
        }

    @Test
    fun `the two preferences do not interfere with each other`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.setDownloadOverWifiOnly(false)
            preferences.setForceOffline(true)

            preferences.downloadOverWifiOnly.first() shouldBe false
            preferences.forceOffline.first() shouldBe true
        }

    @Test
    fun `emits every Wi-Fi-only change to observers`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.downloadOverWifiOnly.test {
                awaitItem() shouldBe true

                preferences.setDownloadOverWifiOnly(false)
                awaitItem() shouldBe false

                cancelAndIgnoreRemainingEvents()
            }
        }
}
