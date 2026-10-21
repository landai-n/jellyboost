package dev.jellyboost.player.session

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import timber.log.Timber

@UnstableApi
internal object PlaybackHealthLogger : AnalyticsListener {
    override fun onAudioUnderrun(
        eventTime: EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        Timber.w(
            "Audio underrun at %d ms: buffer %d bytes (%d ms), %d ms since the last feed",
            eventTime.currentPlaybackPositionMs,
            bufferSize,
            bufferSizeMs,
            elapsedSinceLastFeedMs,
        )
    }

    override fun onDroppedVideoFrames(
        eventTime: EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        Timber.w(
            "Dropped %d video frames over %d ms at %d ms",
            droppedFrames,
            elapsedMs,
            eventTime.currentPlaybackPositionMs,
        )
    }

    override fun onAudioSinkError(
        eventTime: EventTime,
        audioSinkError: Exception,
    ) {
        Timber.w(audioSinkError, "Audio sink error at %d ms", eventTime.currentPlaybackPositionMs)
    }
}
