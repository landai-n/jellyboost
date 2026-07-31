package dev.jellyboost.feature.auth

import dev.jellyboost.core.network.model.ResolvedServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the server the user picked on ServerSetup over to the Login screen.
 *
 * `Routes.Login` is deliberately argument-free: [ResolvedServer] is a `:core:network` model and
 * turning it into a navigation argument would either leak transport details into `:core:common`
 * or put a full server URL into the back-stack bundle. Instead ServerSetup writes the resolved
 * server here and Login reads it on init — see DECISIONS.md, 2026-07-28, "the resolved server
 * travels between auth screens in a holder, not in the route".
 *
 * Lifetime: written on a successful address resolution, cleared on a successful sign-in (or when
 * the user backs out of Login). A Login screen that finds it empty — the app was killed halfway
 * through the flow — simply bounces back to ServerSetup.
 */
@Singleton
internal class PendingServerStore
    @Inject
    constructor() {
        @Volatile
        private var pending: ResolvedServer? = null

        /** The server ServerSetup last resolved, or `null` when the flow has not started. */
        val server: ResolvedServer?
            get() = pending

        /** Records [resolved] as the server the Login screen should authenticate against. */
        fun set(resolved: ResolvedServer) {
            pending = resolved
        }

        /** Drops the pending server; called once the session it produced is established. */
        fun clear() {
            pending = null
        }
    }
