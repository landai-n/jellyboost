package dev.jellyboost.player.music.di

import javax.inject.Qualifier

/**
 * Process-lifetime (`@Singleton`, never cancelled) and **single-threaded** — its
 * `limitedParallelism(1)` is `MusicPlaybackController`'s only synchronization for the plain fields
 * four concurrent sources mutate. Widening the parallelism breaks that class.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class MusicSessionScope
