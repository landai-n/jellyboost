package dev.jellyboost.player.session

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import dev.jellyboost.player.model.AudioSidecarSpec
import dev.jellyboost.player.model.PlaybackMediaItemSpec

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

/**
 * The URI is all of it: a merge child's tracks cannot be named, so `TrackSelectionController` finds
 * them by child *position*, and the picker draws labels from the cached `BaseItemDto` instead.
 * Anything set here would be written and never read.
 */
internal fun AudioSidecarSpec.toMediaItem(): MediaItem = MediaItem.fromUri(uri.toUri())
