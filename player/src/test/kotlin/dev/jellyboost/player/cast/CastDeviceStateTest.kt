package dev.jellyboost.player.cast

import com.google.android.gms.cast.framework.CastState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the `CastState` → [CastDeviceState] table.
 *
 * The whole reason this mapping is a free function rather than a `when` buried in a listener
 * callback: the listener needs a live Cast stack and a Play-services device, the table needs
 * neither. It is also the one place that decides whether the cast button is drawn at all, which on
 * a device without Google Play services is the difference between a hidden button and a crash.
 */
class CastDeviceStateTest {
    @Test
    fun `no receivers on the network yet`() {
        castDeviceStateOf(CastState.NO_DEVICES_AVAILABLE, deviceName = null) shouldBe CastDeviceState.NoDevices
    }

    @Test
    fun `receivers around, nothing connected`() {
        castDeviceStateOf(CastState.NOT_CONNECTED, deviceName = null) shouldBe CastDeviceState.Available
    }

    @Test
    fun `a session being established`() {
        castDeviceStateOf(CastState.CONNECTING, deviceName = null) shouldBe CastDeviceState.Connecting
    }

    @Test
    fun `connected, carrying the receiver's name`() {
        castDeviceStateOf(CastState.CONNECTED, deviceName = "Living Room TV") shouldBe
            CastDeviceState.Connected("Living Room TV")
    }

    @Test
    fun `connected before the framework has published a name`() {
        castDeviceStateOf(CastState.CONNECTED, deviceName = null) shouldBe CastDeviceState.Connected(null)
    }

    @Test
    fun `the device name is ignored while nothing is connected`() {
        // The name survives a disconnect in the framework's own accessors for a moment; it must not
        // leak into a state that claims a session.
        castDeviceStateOf(CastState.NOT_CONNECTED, deviceName = "Living Room TV") shouldBe
            CastDeviceState.Available
    }

    @Test
    fun `a code this build does not know is treated as nothing to cast to`() {
        // Never Unavailable: the stack answering at all proves it is up, and Unavailable is the one
        // state that hides the button for the rest of the process.
        castDeviceStateOf(UNKNOWN_CAST_STATE, deviceName = null) shouldBe CastDeviceState.NoDevices
    }

    @Test
    fun `a device with no Google Play services never leaves Unavailable`() {
        // `CastAvailability.initialize` returns before touching the framework there, so nothing ever
        // publishes a state — the initial one has to be the one that draws no button.
        CastAvailability().state.value shouldBe CastDeviceState.Unavailable
    }

    private companion object {
        /** Outside the four `CastState` codes this build of the framework defines. */
        const val UNKNOWN_CAST_STATE = 99
    }
}
