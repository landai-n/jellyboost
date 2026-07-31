package dev.jellyboost.core.datastore

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [DeviceIdProvider].
 *
 * What is actually being defended here: a Jellyfin server keeps one access token per
 * (user, device id), so an id that is not stable across restarts logs the user out, and an id
 * that is not unique per installation makes two installs revoke each other's session — the bug
 * that motivated this class.
 */
class DeviceIdProviderTest {
    /** In-memory [DeviceIdStore]; a second provider over the same instance models a restart. */
    private class FakeDeviceIdStore(
        private var stored: String? = null,
    ) : DeviceIdStore {
        var writes: Int = 0
            private set

        override fun read(): String? = stored

        override fun write(id: String) {
            stored = id
            writes++
        }
    }

    @Test
    fun `uses the persisted device id when one is stored`() {
        val store = mockk<DeviceIdStore>()
        every { store.read() } returns "already-mine"

        DeviceIdProvider(store).deviceId shouldBe "already-mine"

        verify(exactly = 0) { store.write(any()) }
    }

    @Test
    fun `generates and persists a device id when none is stored`() {
        val store = mockk<DeviceIdStore>()
        every { store.read() } returns null
        every { store.write(any()) } just Runs

        val generated = DeviceIdProvider(store).deviceId

        verify(exactly = 1) { store.write(generated) }
    }

    @Test
    fun `the generated device id is a random uuid`() {
        val provider = DeviceIdProvider(FakeDeviceIdStore())

        UUID.fromString(provider.deviceId).toString() shouldBe provider.deviceId
    }

    @Test
    fun `resolves the device id once and then serves it from memory`() {
        val store = mockk<DeviceIdStore>()
        every { store.read() } returns null
        every { store.write(any()) } just Runs

        val provider = DeviceIdProvider(store)
        val first = provider.deviceId

        provider.deviceId shouldBe first
        provider.deviceId shouldBe first
        verify(exactly = 1) { store.read() }
        verify(exactly = 1) { store.write(any()) }
    }

    @Test
    fun `a restart reuses the id the previous run persisted`() {
        val store = FakeDeviceIdStore()

        val firstRun = DeviceIdProvider(store).deviceId
        // A second provider over the same storage: this is what an app restart looks like.
        val secondRun = DeviceIdProvider(store).deviceId

        secondRun shouldBe firstRun
        store.writes shouldBe 1
    }

    @Test
    fun `two installations with separate storage get different device ids`() {
        val debugInstall = DeviceIdProvider(FakeDeviceIdStore()).deviceId
        val releaseInstall = DeviceIdProvider(FakeDeviceIdStore()).deviceId

        releaseInstall shouldNotBe debugInstall
    }

    @Test
    fun `a blank stored value is replaced instead of sent to the server`() {
        val store = FakeDeviceIdStore(stored = "   ")

        val deviceId = DeviceIdProvider(store).deviceId

        deviceId.isNotBlank() shouldBe true
        store.writes shouldBe 1
    }
}
