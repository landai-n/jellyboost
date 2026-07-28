package dev.jellyfinnative.player.session

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec

/**
 * Converts the resolver's plain description into an ExoPlayer [MediaItem].
 *
 * The only Android-typed step in the URL pipeline, kept to one function so the decisions that lead
 * here stay testable off-device.
 */
internal fun PlaybackMediaItemSpec.toMediaItem(): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(mediaId)
        .setUri(uri.toUri())
        .setMimeType(mimeType)
        .setSubtitleConfigurations(
            subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration
                    .Builder(subtitle.uri.toUri())
                    .setId(subtitle.id)
                    .setMimeType(subtitle.mimeType)
                    .setLabel(subtitle.label)
                    .setLanguage(subtitle.language)
                    .build()
            },
        ).build()
