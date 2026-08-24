package dev.jellyboost.core.common.di

import javax.inject.Qualifier

/**
 * Qualifies the application's main thread, for the libraries that insist on it.
 *
 * Media3 is why this lives outside `:player`: `Transformer` (and `ExoPlayer`) assert they are created,
 * started and cancelled on one thread with a `Looper`, and `AudioSidecarExtractor` in `:data:downloads` needs
 * that guarantee but cannot see `:player`'s qualifier. Injected rather than referencing `Dispatchers.Main`,
 * which does not exist in a plain JVM unit test and would leave the transmux path untestable off a device.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
