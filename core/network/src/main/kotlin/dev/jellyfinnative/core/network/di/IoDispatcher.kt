package dev.jellyfinnative.core.network.di

import javax.inject.Qualifier

/**
 * Qualifies the dispatcher used for blocking network and discovery work.
 *
 * Injected rather than referenced as `Dispatchers.IO` so that unit tests can substitute a
 * `TestDispatcher` and run the Quick Connect polling loop on virtual time.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
