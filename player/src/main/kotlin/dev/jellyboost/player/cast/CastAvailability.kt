package dev.jellyboost.player.cast

import android.content.Context
import androidx.annotation.MainThread
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single door between the app and Google Cast (docs/notes/chromecast-m12-plan.md, Phase 1).
 *
 * Two jobs. It owns the process-wide [CastContext] — created once, from `MainActivity.onCreate`,
 * and only when Play services are actually there — and it publishes what the Cast world looks like
 * right now as [state], a GMS-free [CastDeviceState] the Compose UI can observe without ever naming
 * a `com.google.android.gms` type.
 *
 * That second job is the load-bearing one. Jellyboost ships one APK for every device, including
 * those with no Play services at all (the plan's "first GMS dependency" risk): every GMS type stays
 * inside this package, nothing outside it may name one, and on a device without Play services
 * nothing here runs past the guard in [initialize]. [state] staying [CastDeviceState.Unavailable] is
 * what stops the cast button — and every Cast class behind it — from ever being touched there.
 */
@Singleton
class CastAvailability
    @Inject
    constructor() {
        // ktlint reads `_x`/`x` as an idiom for exposing a mutable field *publicly*, and refuses it
        // when the read-only half is not `public`. Here [state] is `internal` because
        // `CastDeviceState` is (audit ARCH-2) — the pairing is otherwise exactly the idiom the rule
        // is about.
        @Suppress("ktlint:standard:backing-property-naming")
        private val _state = MutableStateFlow<CastDeviceState>(CastDeviceState.Unavailable)

        /**
         * What casting looks like right now; [CastDeviceState.Unavailable] until (and unless)
         * [initialize] finds a working Cast stack.
         */
        internal val state: StateFlow<CastDeviceState> = _state.asStateFlow()

        /**
         * The shared [CastContext], or `null` while unavailable — the sender-side API the later
         * phases (`CastPlayerHandle`, `CastSessionCoordinator`) hang off. Callers outside this
         * package have no business with it; the type is what confines them.
         *
         * `@Volatile` for the same reason `CastMetadataHolder`'s field is: today every write and
         * read happens on the main thread, but that is a convention rather than a checked property
         * of a `@Singleton`, and a future off-main reader seeing a stale `null` would silently
         * disable casting (audit CAST-07).
         */
        @Volatile
        internal var castContext: CastContext? = null
            private set

        /** One attempt per process — a stack that refused to start will not start on a retry. */
        @Volatile
        private var initializationStarted = false

        /**
         * Brings the Cast stack up, once per process.
         *
         * Guarded on Play services being present *and* usable: `getSharedInstance` on a device
         * without them throws, and a hard crash on launch is the one outcome a cast button is not
         * worth. Failure of any kind simply leaves [state] at [CastDeviceState.Unavailable].
         *
         * Cheap enough for `onCreate`: the framework does its work on the supplied executor and
         * calls back on the main thread, which is where the listener registration below belongs.
         *
         * @param context any context; only the application context is retained.
         */
        @MainThread
        fun initialize(context: Context) {
            if (initializationStarted) return
            initializationStarted = true

            val appContext = context.applicationContext
            val playServicesStatus =
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
            if (playServicesStatus != ConnectionResult.SUCCESS) {
                Timber.i("Google Play services unavailable (status %d) — casting is off", playServicesStatus)
                return
            }

            // One thread, used once: the framework's initialisation is I/O-ish and must not run on
            // the main thread, and the executor has no work left after the task completes.
            val executor = Executors.newSingleThreadExecutor()
            CastContext
                .getSharedInstance(appContext, executor)
                .addOnSuccessListener { shared ->
                    castContext = shared
                    // Never removed: the CastContext is process-wide and outlives everything that
                    // could plausibly unregister from it.
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
         * The friendly name of whatever is connected, or `null`.
         *
         * Defensive: the session accessors are documented to throw when the session is not in the
         * state the caller assumed, and this runs from a listener callback that fires *during* the
         * transitions where that is most likely.
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

/**
 * What the app knows about casting, with no Google Play services types in sight — the whole point,
 * so the UI can observe it on a device that has no Cast stack to observe.
 */
internal sealed interface CastDeviceState {
    /** No Cast stack: no Play services, or the framework failed to start. Draw nothing. */
    data object Unavailable : CastDeviceState

    /** Cast works, but no receiver has been discovered on this network yet. */
    data object NoDevices : CastDeviceState

    /** Receivers are around and nothing is connected — the button is worth offering. */
    data object Available : CastDeviceState

    /** A session is being established. */
    data object Connecting : CastDeviceState

    /**
     * Connected and playing (or ready to).
     *
     * @property deviceName the receiver's friendly name; `null` when the framework has not
     *   published one yet, which callers show as a generic "casting" rather than an empty label.
     */
    data class Connected(
        val deviceName: String?,
    ) : CastDeviceState
}

/**
 * The Cast framework's `CastState` int, as a [CastDeviceState].
 *
 * Kept pure and separate from [CastAvailability] so the table can be tested without a Cast stack:
 * the `CastState` constants are Java compile-time constants, inlined by the compiler, so nothing
 * here loads a GMS class at runtime — reading this function's bytecode is the fastest way to
 * confirm the mapping is honest.
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
        // A code this build does not know about. Treated as "nothing to cast to" rather than as
        // Unavailable: the stack is demonstrably up (it just told us something), so hiding the
        // button for good would be the wrong lie — MediaRouter still hides its own button while
        // there is nothing to route to.
        else -> CastDeviceState.NoDevices
    }
