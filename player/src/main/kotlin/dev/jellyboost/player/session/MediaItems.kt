package dev.jellyboost.player.session

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import dev.jellyboost.player.model.AudioSidecarSpec
import dev.jellyboost.player.model.PlaybackMediaItemSpec

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

/**
 * An audio sidecar as its own [MediaItem], for [ExoPlayerHandle.prepare] to merge alongside the
 * main one.
 *
 * The URI is all of it. There is no id to set — the merge does not let us name a child's tracks, so
 * selection navigates by child *position* instead
 * (`TrackSelectionController.selectAudio`) — and no label or language either: the picker draws
 * `PlaybackTrack`s built from the cached `BaseItemDto`, never ExoPlayer's own metadata, so anything
 * set here would be written and never read.
 */
internal fun AudioSidecarSpec.toMediaItem(): MediaItem = MediaItem.fromUri(uri.toUri())
