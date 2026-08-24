package dev.jellyboost.player.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single door between the app and Google Cast. One APK ships to devices with no Play services,
 * so every `com.google.android.gms` type must stay inside this package: nothing outside it may name
 * one, and [state] staying [CastDeviceState.Unavailable] is what keeps those classes untouched there.
 */
@Singleton
class CastAvailability
    @Inject
    constructor() {
        // The rule only accepts `_x`/`x` when the read-only half is public; [state] is internal
        // because `CastDeviceState` is.
        @Suppress("ktlint:standard:backing-property-naming")
        private val _state = MutableStateFlow<CastDeviceState>(CastDeviceState.Unavailable)

        /** [CastDeviceState.Unavailable] until (and unless) [initialize] finds a working Cast stack. */
        internal val state: StateFlow<CastDeviceState> = _state.asStateFlow()

        /** `@Volatile`: main-thread-only access is convention here, and a stale `null` disables casting. */
        @Volatile
        internal var castContext: CastContext? = null
            private set

        /** One attempt per process — a stack that refused to start will not start on a retry. */
        @Volatile
        private var initializationStarted = false

        /**
         * Brings the Cast stack up, once per process. Must stay guarded on Play services being
         * present: `getSharedInstance` throws without them, on the path to the first frame.
         *
         * `isGooglePlayServicesAvailable` is a binder round trip, so it must stay off the main
         * thread; `getSharedInstance` must stay *on* it — the `CastContext` binds to that looper.
         *
         * @param context any context; only the application context is retained.
         */
        @MainThread
        fun initialize(context: Context) {
            if (initializationStarted) return
            initializationStarted = true

            val appContext = context.applicationContext
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                val playServicesStatus =
                    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
                if (playServicesStatus != ConnectionResult.SUCCESS) {
                    Timber.i("Google Play services unavailable (status %d) — casting is off", playServicesStatus)
                    executor.shutdown()
                    return@execute
                }
                Handler(Looper.getMainLooper()).post { startCastContext(appContext, executor) }
            }
        }

        /** The main-thread half: `CastContext` binds to the looper it is created on. */
        @MainThread
        private fun startCastContext(
            appContext: Context,
            executor: ExecutorService,
        ) {
            CastContext
                .getSharedInstance(appContext, executor)
                .addOnSuccessListener { shared ->
                    castContext = shared
                    // Never removed: the CastContext is process-wide and outlives every possible owner.
                    shared.addCastStateListener { castState -> publish(castState) }
                    publish(shared.castState)
                }.addOnFailureListener { error ->
                    Timber.w(error, "Could not initialise the Cast framework — casting is off")
                }.addOnCompleteListener { executor.shutdown() }
        }

        private fun publish(castState: Int) {
            _state.value = castDeviceStateOf(castState, connectedDeviceName())
        }

        /**
         * The session accessors throw when the session is not in the assumed state, and this runs
         * from a listener that fires *during* those transitions.
         */
        private fun connectedDeviceName(): String? =
            runCatching {
                castContext
                    ?.sessionManager
                    ?.currentCastSession
                    ?.castDevice
                    ?.friendlyName
            }.getOrNull()
    }

/** Casting state with no GMS types in it, so the UI can observe it where there is no Cast stack. */
internal sealed interface CastDeviceState {
    /** No Play services, or the framework failed to start. Draw nothing. */
    data object Unavailable : CastDeviceState

    /** Cast works, but no receiver has been discovered on this network yet. */
    data object NoDevices : CastDeviceState

    data object Available : CastDeviceState

    data object Connecting : CastDeviceState

    /**
     * @property deviceName `null` until the framework publishes one; callers show a generic
     *   "casting" rather than an empty label.
     */
    data class Connected(
        val deviceName: String?,
    ) : CastDeviceState
}

/**
 * Kept out of [CastAvailability] so it is testable without a Cast stack: the `CastState` constants
 * are Java compile-time constants, so nothing here loads a GMS class at runtime.
 *
 * @param deviceName only meaningful for [CastState.CONNECTED]; ignored otherwise.
 */
internal fun castDeviceStateOf(
    castState: Int,
    deviceName: String?,
): CastDeviceState =
    when (castState) {
        CastState.NO_DEVICES_AVAILABLE -> CastDeviceState.NoDevices
        CastState.NOT_CONNECTED -> CastDeviceState.Available
        CastState.CONNECTING -> CastDeviceState.Connecting
        CastState.CONNECTED -> CastDeviceState.Connected(deviceName)
        // An unknown code means the stack is up, so NoDevices rather than Unavailable: the button
        // hides for now instead of for good.
        else -> CastDeviceState.NoDevices
    }
