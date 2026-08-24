package dev.jellyboost.core.common.di

import javax.inject.Qualifier

/**
 * Blocking network and discovery work. Injected rather than referenced as `Dispatchers.IO` so unit tests can
 * run loops like Quick Connect polling on virtual time.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
