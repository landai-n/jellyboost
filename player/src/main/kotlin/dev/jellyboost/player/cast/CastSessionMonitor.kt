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

internal interface CastSessionMonitor {
    /**
     * Starts watching, once; [listener] is called on the main thread.
     *
     * An already-connected session is reported as a start: the framework does not replay it, so connecting
     * before opening an item would otherwise never route to the receiver.
     */
    fun start(listener: CastSessionListener)
}

internal interface CastSessionListener {
    /** @param modelName input to `CastReceiverClass.fromModelName`; the default classifies as the legacy profile. */
    fun onSessionStarted(
        deviceName: String?,
        modelName: String? = null,
    )

    fun onSessionEnded()
}

/**
 * Every `com.google.android.gms` type in the cast session lifecycle is confined here, and registration waits on
 * [CastAvailability]: a device without Play services must never load a Cast class.
 */
@Singleton
internal class GmsCastSessionMonitor
    @Inject
    constructor(
        private val availability: CastAvailability,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
    ) : CastSessionMonitor {
        /**
         * Main dispatcher: `SessionManager` listeners must be registered from the main thread, and the callbacks
         * reach main-thread-only `PlayerHandle`. Never cancelled — the Cast session outlives every screen.
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
                    ?.let { listener.onSessionStarted(it.deviceName(), it.deviceModel()) }
            }
        }

        /**
         * Never unregistered: the manager is process-wide, as is the coordinator behind this.
         *
         * Resumed and started are deliberately the same event — a session restored across a process restart is
         * still a receiver.
         */
        private fun sessionListener(listener: CastSessionListener) =
            object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(
                    session: CastSession,
                    sessionId: String,
                ) = listener.onSessionStarted(session.deviceName(), session.deviceModel())

                override fun onSessionResumed(
                    session: CastSession,
                    wasSuspended: Boolean,
                ) = listener.onSessionStarted(session.deviceName(), session.deviceModel())

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

        /** The `castDevice` accessors throw mid-transition. */
        private fun CastSession.deviceName(): String? = runCatching { castDevice?.friendlyName }.getOrNull()

        private fun CastSession.deviceModel(): String? = runCatching { castDevice?.modelName }.getOrNull()
    }
