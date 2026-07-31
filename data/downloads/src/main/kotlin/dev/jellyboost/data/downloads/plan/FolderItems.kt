package dev.jellyboost.data.downloads.plan

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * `true` when the item is a **folder** — a series, a season, a box set, a library — rather than a
 * single video file.
 *
 * The whole download pipeline turns on this one question, because a folder has no file to fetch:
 * `/Items/{id}/Download` answers `400` for one, which is exactly the failure this predicate exists
 * to make impossible (DECISIONS.md, 2026-07-29). `DownloadEnqueuer` uses it to decide whether to
 * *expand* the item into its episodes, and [DownloadFilePlanner] uses it as the last guard before a
 * URL is built — which is why it lives here, in `.plan`, rather than in the root package: the
 * planner is upstream of the enqueuer, and a predicate the planner imports upwards was one half of
 * the root↔plan package cycle (audit ARCH-05).
 *
 * `isFolder` is the server's own answer and is authoritative; the kind list is the fallback for a
 * DTO that reached us without it (an older cached blob, a hand-built test fixture). Deliberately
 * *not* "has no media source": a movie fetched with a lean field set has no `mediaSources` either,
 * and treating that as a folder would refuse a download that works perfectly.
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
