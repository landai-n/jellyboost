package dev.jellyboost.player.music.di

import javax.inject.Qualifier

/**
 * Qualifies the process-lifetime scope the music queue is orchestrated on.
 *
 * The queue is not a screen: the user backs out of the album, the app is backgrounded, the screen
 * goes off, and the album keeps playing with its reports still going to the server. The scope is
 * therefore a `@Singleton` and is never cancelled, the same shape as `@SyncPlayScope`.
 *
 * **Single-threaded, and that is `MusicPlaybackController`'s synchronization.** The controller
 * keeps the queue, the current index and the open server session in plain fields and mutates them
 * from four concurrent sources — the caller's `play()`, the player's event flow, the position
 * ticker and the reporting ticker. `limitedParallelism(1)` serialises all of them with a
 * happens-before edge between each, which is what lets those fields need no locks, exactly as
 * SyncPlay's scope does for its own bookkeeping.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class MusicSessionScope
