package dev.jellyboost.core.common.di

import javax.inject.Qualifier

/**
 * Qualifies the process-lifetime [kotlinx.coroutines.CoroutineScope] that app-wide, never-cancelled
 * work runs in — the connectivity monitor, the reachability probe, and the fire-and-forget
 * write-through into the browse cache.
 *
 * Injected rather than created ad hoc so tests can substitute a `TestScope` and drive that work on
 * virtual time.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
