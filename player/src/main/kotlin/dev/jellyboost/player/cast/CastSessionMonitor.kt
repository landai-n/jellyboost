package dev.jellyboost.player.cast

import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dev.jellyboost.core.common.di.MainDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "A receiver appeared" and "the receiver went away", with no Cast stack in the signature.
 *
 * The seam [CastSessionCoordinator] is written against, and the reason it can be unit tested: the
 * coordinator's job — flipping the routing handle, sending the final stop report, keeping the
 * progress ticker alive off-screen — is ours and is worth pinning, while `SessionManagerListener`
 * needs Play services, a `CastContext` and a receiver on the network to say anything at all.
 *
 * It also carries the *waiting*. `CastAvailability` brings the Cast stack up asynchronously from
 * `MainActivity.onCreate`, so there is a window in which there is no `SessionManager` to register
 * with; the coordinator has no business knowing that exists.
 */
internal interface CastSessionMonitor {
    /**
     * Starts watching, once. [listener] is called on the main thread.
     *
     * A session that is *already* connected when watching starts is reported as a start — the
     * framework does not replay it, and the everyday case (connect from the home screen, then open
     * an item) would otherwise never route to the receiver.
     */
    fun start(listener: CastSessionListener)
}

/** What [CastSessionMonitor] reports. */
internal interface CastSessionListener {
    fun onSessionStarted(deviceName: String?)

    fun onSessionEnded()
}

/**
 * The real [CastSessionMonitor], over the framework's `SessionManager`.
 *
 * Every `com.google.android.gms` type in the cast session lifecycle is confined here. Registration
 * waits for `CastAvailability` to publish anything but
 * [CastDeviceState.Unavailable][CastDeviceState.Unavailable], which is the same guard the route
 * button uses and the one that keeps a Play-services-less device from loading a Cast class.
 */
@Singleton
internal class GmsCastSessionMonitor
    @Inject
    constructor(
        private val availability: CastAvailability,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
    ) : CastSessionMonitor {
        /**
         * Its own scope, on the main dispatcher.
         *
         * `SessionManager`'s listeners must be registered from the main thread, and the callbacks
         * this ends up driving reach `PlayerHandle`, which is main-thread-only throughout. A
         * `SupervisorJob` that is never cancelled, because the Cast session outlives every screen —
         * which is the whole point of the coordinator behind it.
         */
        private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

        private var started = false

        override fun start(listener: CastSessionListener) {
            if (started) return
            started = true
            scope.launch {
                availability.state.first { it != CastDeviceState.Unavailable }
                val manager = availability.castContext?.sessionManager
                if (manager == null) {
                    Timber.w("Cast reported itself available with no session manager; not watching sessions")
                    return@launch
                }
                manager.addSessionManagerListener(sessionListener(listener), CastSession::class.java)
                manager.currentCastSession
                    ?.takeIf { it.isConnected }
                    ?.let { listener.onSessionStarted(it.deviceName()) }
            }
        }

        /**
         * Never unregistered: the manager is process-wide and so is the coordinator behind this.
         *
         * Resumed and started are the same event here. The framework resumes a session it saved
         * across a process restart (`setResumeSavedSession(true)`), and from playback's point of
         * view a resumed receiver is a receiver.
         */
        private fun sessionListener(listener: CastSessionListener) =
            object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(
                    session: CastSession,
                    sessionId: String,
                ) = listener.onSessionStarted(session.deviceName())

                override fun onSessionResumed(
                    session: CastSession,
                    wasSuspended: Boolean,
                ) = listener.onSessionStarted(session.deviceName())

                override fun onSessionEnded(
                    session: CastSession,
                    error: Int,
                ) = listener.onSessionEnded()

                override fun onSessionSuspended(
                    session: CastSession,
                    reason: Int,
                ) = Unit

                override fun onSessionStarting(session: CastSession) = Unit

                override fun onSessionResuming(
                    session: CastSession,
                    sessionId: String,
                ) = Unit

                override fun onSessionEnding(session: CastSession) = Unit

                override fun onSessionStartFailed(
                    session: CastSession,
                    error: Int,
                ) = listener.onSessionEnded()

                override fun onSessionResumeFailed(
                    session: CastSession,
                    error: Int,
                ) = listener.onSessionEnded()
            }

        /** Defensive for the same reason `CastAvailability` is: the accessors throw mid-transition. */
        private fun CastSession.deviceName(): String? = runCatching { castDevice?.friendlyName }.getOrNull()
    }
