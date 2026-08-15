package dev.jellyboost.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
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

    // ---- M9: segment skip + picture-in-picture ---------------------------------------------------

    @Test
    fun `both segment skip modes default to showing a button`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.introSkipMode.first() shouldBe SegmentSkipMode.SHOW_BUTTON
            preferences.outroSkipMode.first() shouldBe SegmentSkipMode.SHOW_BUTTON
        }

    @Test
    fun `a segment skip mode survives a round trip through storage`() =
        runTest {
            val store = dataStore(this)

            DataStoreAppPreferences(store).setIntroSkipMode(SegmentSkipMode.AUTO_SKIP)

            DataStoreAppPreferences(store).introSkipMode.first() shouldBe SegmentSkipMode.AUTO_SKIP
        }

    @Test
    fun `the intro and outro modes are independent`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.setIntroSkipMode(SegmentSkipMode.AUTO_SKIP)
            preferences.setOutroSkipMode(SegmentSkipMode.OFF)

            preferences.introSkipMode.first() shouldBe SegmentSkipMode.AUTO_SKIP
            preferences.outroSkipMode.first() shouldBe SegmentSkipMode.OFF
        }

    @Test
    fun `an unrecognised stored skip mode degrades to the default`() =
        runTest {
            val store = dataStore(this)
            // What a downgrade, or a renamed constant, leaves behind in the file.
            store.edit { it[stringPreferencesKey(PreferenceKeys.SEGMENT_SKIP_INTRO)] = "SKIP_EVERYTHING" }

            DataStoreAppPreferences(store).introSkipMode.first() shouldBe SegmentSkipMode.SHOW_BUTTON
        }

    @Test
    fun `picture in picture on leave defaults to on`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.pipOnLeave.first() shouldBe true
        }

    @Test
    fun `turning picture in picture off survives a round trip through storage`() =
        runTest {
            val store = dataStore(this)

            DataStoreAppPreferences(store).setPipOnLeave(false)

            DataStoreAppPreferences(store).pipOnLeave.first() shouldBe false
        }

    // ---- download quality (M9) ------------------------------------------------------------------

    @Test
    fun `download quality defaults to the original file`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.downloadQuality.first() shouldBe DownloadQuality.ORIGINAL
        }

    @Test
    fun `a download quality survives a round trip through storage`() =
        runTest {
            val store = dataStore(this)

            DataStoreAppPreferences(store).setDownloadQuality(DownloadQuality.MEDIUM)

            DataStoreAppPreferences(store).downloadQuality.first() shouldBe DownloadQuality.MEDIUM
        }

    @Test
    fun `an unrecognised stored download quality degrades to the original file`() =
        runTest {
            val store = dataStore(this)
            // What a downgrade looks like: a name only a newer build knows.
            store.edit { it[stringPreferencesKey(PreferenceKeys.DOWNLOAD_QUALITY)] = "POTATO" }

            DataStoreAppPreferences(store).downloadQuality.first() shouldBe DownloadQuality.ORIGINAL
        }

    // ---- storage location ------------------------------------------------------------------------

    @Test
    fun `no storage volume is stored until the user picks one`() =
        runTest {
            // Absent, not "primary": the default and an explicit choice of the built-in volume are
            // deliberately indistinguishable, so a device whose volume ids change still resolves.
            DataStoreAppPreferences(dataStore(this)).downloadStorageVolumeId.first() shouldBe null
        }

    @Test
    fun `the chosen storage volume survives a round trip through storage`() =
        runTest {
            val store = dataStore(this)

            DataStoreAppPreferences(store).setDownloadStorageVolumeId("1A2B-3C4D")

            DataStoreAppPreferences(store).downloadStorageVolumeId.first() shouldBe "1A2B-3C4D"
        }

    @Test
    fun `clearing the chosen storage volume restores the default`() =
        runTest {
            val store = dataStore(this)
            DataStoreAppPreferences(store).setDownloadStorageVolumeId("1A2B-3C4D")

            DataStoreAppPreferences(store).setDownloadStorageVolumeId(null)

            DataStoreAppPreferences(store).downloadStorageVolumeId.first() shouldBe null
        }

    @Test
    fun `a blank stored storage volume reads as no choice at all`() =
        runTest {
            val store = dataStore(this)
            // Only a bad write can produce this, and the default volume is the safe answer to it.
            store.edit { it[stringPreferencesKey(PreferenceKeys.DOWNLOAD_STORAGE_VOLUME)] = "  " }

            DataStoreAppPreferences(store).downloadStorageVolumeId.first() shouldBe null
        }

    // ---- max streaming bitrate --------------------------------------------------------------------

    @Test
    fun `no streaming ceiling is stored until one is measured`() =
        runTest {
            DataStoreAppPreferences(dataStore(this)).maxStreamingBitrate.first() shouldBe null
        }

    @Test
    fun `a measured streaming ceiling survives a round trip through storage`() =
        runTest {
            val store = dataStore(this)

            DataStoreAppPreferences(store).setMaxStreamingBitrate(12_000_000)

            // A second instance over the same file: this is the prior a fresh app start reads.
            DataStoreAppPreferences(store).maxStreamingBitrate.first() shouldBe 12_000_000
        }

    @Test
    fun `clearing the streaming ceiling forgets it entirely`() =
        runTest {
            val store = dataStore(this)
            DataStoreAppPreferences(store).setMaxStreamingBitrate(12_000_000)

            DataStoreAppPreferences(store).setMaxStreamingBitrate(null)

            DataStoreAppPreferences(store).maxStreamingBitrate.first() shouldBe null
        }

    @Test
    fun `a non-positive stored streaming ceiling reads as never measured`() =
        runTest {
            val store = dataStore(this)
            // Only a bad write can produce this, and no cap is a better answer than a zero one.
            store.edit { it[intPreferencesKey(PreferenceKeys.MAX_STREAMING_BITRATE)] = 0 }

            DataStoreAppPreferences(store).maxStreamingBitrate.first() shouldBe null
        }

    @Test
    fun `emits every download quality change to observers`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.downloadQuality.test {
                awaitItem() shouldBe DownloadQuality.ORIGINAL

                preferences.setDownloadQuality(DownloadQuality.LOW)
                awaitItem() shouldBe DownloadQuality.LOW

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `emits every skip mode change to observers`() =
        runTest {
            val preferences = DataStoreAppPreferences(dataStore(this))

            preferences.outroSkipMode.test {
                awaitItem() shouldBe SegmentSkipMode.SHOW_BUTTON

                preferences.setOutroSkipMode(SegmentSkipMode.OFF)
                awaitItem() shouldBe SegmentSkipMode.OFF

                cancelAndIgnoreRemainingEvents()
            }
        }
}
