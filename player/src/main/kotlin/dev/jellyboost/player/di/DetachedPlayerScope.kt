package dev.jellyboost.player.di

import javax.inject.Qualifier

/**
 * Process-lifetime: the stop report (resume position, watched flag, the call that kills a
 * server-side ffmpeg process) is issued while the ViewModel is torn down, after `viewModelScope`
 * has been cancelled and would drop the work.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DetachedPlayerScope
