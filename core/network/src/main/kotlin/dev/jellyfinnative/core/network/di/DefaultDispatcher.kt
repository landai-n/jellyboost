package dev.jellyfinnative.core.network.di

import javax.inject.Qualifier

/**
 * Qualifies the dispatcher CPU work runs on — list grouping, sorting, projections.
 *
 * Distinct from [IoDispatcher], which is sized for *blocking* calls: a projection that ran there
 * would compete with the queries feeding it. Injected rather than referenced as
 * `Dispatchers.Default` so that a unit test can put the work on its own scheduler and keep
 * `advanceUntilIdle()` deterministic.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
