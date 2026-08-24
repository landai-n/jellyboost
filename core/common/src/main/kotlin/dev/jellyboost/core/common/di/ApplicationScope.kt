package dev.jellyboost.core.common.di

import javax.inject.Qualifier

/**
 * The process-lifetime scope app-wide, never-cancelled work runs in. Injected rather than created ad hoc so
 * tests can substitute a `TestScope` and drive that work on virtual time.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
