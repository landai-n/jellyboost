package dev.jellyboost.data.downloads.plan

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * `true` when the item is a **folder** — a series, a season, a box set, a library — rather than a
 * single video file.
 *
 * The whole download pipeline turns on this one question, because a folder has no file to fetch:
 * `/Items/{id}/Download` answers `400` for one. It lives in `.plan` rather than the root package
 * because the planner is upstream of the enqueuer, and a predicate the planner imported upwards was
 * one half of the root↔plan package cycle.
 *
 * `isFolder` is the server's own answer and is authoritative; the kind list is the fallback for a DTO
 * that reached us without it. Deliberately *not* "has no media source": a movie fetched with a lean
 * field set has no `mediaSources` either, and treating that as a folder would refuse a download that
 * works perfectly.
 */
internal val BaseItemDto.isFolderItem: Boolean
    get() = isFolder == true || type in FOLDER_KINDS

/** Kinds that are containers whatever `isFolder` says. */
private val FOLDER_KINDS =
    setOf(
        BaseItemKind.SERIES,
        BaseItemKind.SEASON,
        BaseItemKind.BOX_SET,
        BaseItemKind.FOLDER,
        BaseItemKind.COLLECTION_FOLDER,
        BaseItemKind.USER_VIEW,
        BaseItemKind.PLAYLIST,
        BaseItemKind.PHOTO_ALBUM,
        BaseItemKind.MUSIC_ALBUM,
        BaseItemKind.MUSIC_ARTIST,
    )
