package dev.jellyboost.feature.auth

import dev.jellyboost.core.network.model.ResolvedServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `Routes.Login` is argument-free on purpose: making [ResolvedServer] a navigation argument would
 * leak transport details into `:core:common` or put a full server URL into the back-stack bundle.
 * A Login screen that finds this empty — killed mid-flow — bounces back to ServerSetup.
 */
@Singleton
internal class PendingServerStore
    @Inject
    constructor() {
        @Volatile
        private var pending: ResolvedServer? = null

        val server: ResolvedServer?
            get() = pending

        fun set(resolved: ResolvedServer) {
            pending = resolved
        }

        fun clear() {
            pending = null
        }
    }
