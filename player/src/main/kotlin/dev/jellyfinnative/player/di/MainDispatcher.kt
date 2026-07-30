package dev.jellyfinnative.player.di

import javax.inject.Qualifier

/**
 * Qualifies the dispatcher that drives the shared `ExoPlayer`.
 *
 * Media3 requires every transport call to be made on the thread the player was built on — the app's
 * main thread. `PlayerViewModel` gets that for free from `viewModelScope`, but SyncPlay's controller
 * runs on its own background scope (`@SyncPlayScope`) and still has to seek, play and pause, so the
 * hop has to be explicit.
 *
 * Injected rather than referenced as `Dispatchers.Main` because `Dispatchers.Main` does not exist in
 * a plain JVM unit test, and because the scheduler's whole behaviour is "apply at an exact instant",
 * which is only testable on a `TestDispatcher`'s virtual clock.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
