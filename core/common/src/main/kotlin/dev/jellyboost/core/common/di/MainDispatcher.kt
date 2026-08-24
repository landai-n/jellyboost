package dev.jellyboost.core.common.di

import javax.inject.Qualifier

/**
 * Qualifies the application's main thread, for the libraries that insist on it.
 *
 * Media3 is the reason this exists outside `:player`: `Transformer` (and `ExoPlayer` before it)
 * asserts that it is created, started and cancelled on one thread with a `Looper`, and the main
 * thread is the only such thread a background component can be sure of. `AudioSidecarExtractor` in
 * `:data:downloads` needs that guarantee for the sidecar transmux and cannot see `:player`'s
 * qualifier, so it depends on this one instead of hard-coding `Dispatchers.Main`, which would leave
 * the whole transmux path untestable off a device.
 *
 * Injected rather than referenced as `Dispatchers.Main` for two reasons: `Dispatchers.Main` does not
 * exist in a plain JVM unit test, and "hops to the main thread" is a claim a `TestDispatcher` can
 * actually hold still.
 *
 * It lives beside [IoDispatcher] and [DefaultDispatcher] in `:core:common`, the one module every
 * other module can see, which is what lets `:data:downloads` and `:player` name the same annotation;
 * the binding stays in `:core:network`'s `NetworkDispatchersModule`.
 *
 * This is the app's only main-thread qualifier: `:player` does not declare a same-named twin of its
 * own.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
