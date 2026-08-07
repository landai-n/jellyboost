package dev.jellyboost.core.common.di

import javax.inject.Qualifier

/**
 * Qualifies the application's main thread, for the libraries that insist on it.
 *
 * Media3 is the reason this exists outside `:player`: `Transformer` (and `ExoPlayer` before it)
 * asserts that it is created, started and cancelled on one thread with a `Looper`, and the main
 * thread is the only such thread a background component can be sure of. `AudioSidecarExtractor` in
 * `:data:downloads` needs that guarantee for the sidecar transmux and cannot see `:player`'s
 * qualifier, which is what left `Dispatchers.Main` hard-coded there and the whole transmux path
 * untestable off a device (audit HYG-11).
 *
 * Injected rather than referenced as `Dispatchers.Main` for two reasons: `Dispatchers.Main` does not
 * exist in a plain JVM unit test, and "hops to the main thread" is a claim a `TestDispatcher` can
 * actually hold still.
 *
 * It lives beside [IoDispatcher] and [DefaultDispatcher] in `:core:common`, the one module every
 * other module can see, which is what lets `:data:downloads` and `:player` name the same annotation
 * (audit ARCH-1; the binding stays in `:core:network`'s `NetworkDispatchersModule`).
 *
 * This is the app's only main-thread qualifier: `:player`'s same-named twin was collapsed onto this
 * one when the SyncPlay structural wave landed (audit HYG-11's plan, executed with HYG-4's sweep).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
