package dev.jellyfinnative.player.di

import javax.inject.Qualifier

/**
 * Qualifies a process-lifetime [kotlinx.coroutines.CoroutineScope] that outlives the player screen.
 *
 * The stop report — resume position, watched flag, and the call that kills a server-side ffmpeg
 * process — is issued while the ViewModel is being torn down, when `viewModelScope` has already
 * been cancelled and would drop the work (docs/PLAN.md, "Playback pipeline" → Reporting).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DetachedPlayerScope
