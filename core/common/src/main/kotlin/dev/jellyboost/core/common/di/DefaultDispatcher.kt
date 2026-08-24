package dev.jellyboost.core.common.di

import javax.inject.Qualifier

/**
 * CPU work — list grouping, sorting, projections. Distinct from [IoDispatcher], which is sized for *blocking*
 * calls: a projection running there would compete with the queries feeding it. Injected so a unit test can put
 * the work on its own scheduler and keep `advanceUntilIdle()` deterministic.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
