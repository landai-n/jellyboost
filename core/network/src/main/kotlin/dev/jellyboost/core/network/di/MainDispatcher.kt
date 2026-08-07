package dev.jellyboost.core.network.di

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
 * It lives beside [IoDispatcher] and [DefaultDispatcher] because that is where this project keeps
 * its dispatcher qualifiers — `:core:common` deliberately has no DI dependency (DECISIONS.md,
 * 2026-07-30, structural batch, divergence 6), and moving all three is the separately-logged ARCH-1.
 *
 * **`:player` has its own `MainDispatcher`, still.** Same name, same meaning, same binding value.
 * Collapsing the two is a pure import swap in five files, but one of them is `SyncPlayController`,
 * which a structural wave owns and which must not be touched in passing. When that wave lands, delete
 * `dev.jellyboost.player.di.MainDispatcher` and its `PlayerModule` provider and re-point the five
 * imports here.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
