package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.model.DownloadQuality
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale

/**
 * Naming rules for what lands on disk (docs/PLAN.md, "Download pipeline" → File plan:
 * `Series - S01E02 - Title/` or `Movie (Year)/`).
 *
 * These names are user-visible — the whole point of the plan's layout is that someone plugging the
 * tablet into a computer can tell what the folders are — so they are built from the item's metadata
 * rather than from its id, and then made safe for a FAT/exFAT volume, which is what an SD card
 * usually is.
 *
 * Everything here is pure: no `Context`, no filesystem. That is deliberate — the naming is the
 * fiddliest part of the file plan and it is fully unit-tested.
 */
internal object DownloadPaths {
    /**
     * Characters no common Android filesystem accepts.
     *
     * `:` and `?` in particular are legal on ext4 but not on the exFAT volumes SD cards ship with,
     * so a film called *Mission: Impossible* would otherwise fail to create its directory on some
     * devices only. Hyphens are deliberately **kept**: the plan's directory format uses ` - ` as
     * its separator, and stripping them would also turn `Spider-Man` into `Spider Man`.
     */
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    /** Runs of whitespace collapse to one space so a trimmed title does not keep a double gap. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Cap on a single path segment. Well under the 255-byte limit every relevant filesystem
     * imposes, with room for multi-byte characters in a title.
     */
    private const val MAX_SEGMENT = 120

    /** Used when an item has no usable name at all — never expected, never allowed to crash. */
    private const val FALLBACK = "download"

    /** Extension assumed when the server tells us nothing about the container. */
    private const val DEFAULT_CONTAINER = "mkv"

    /**
     * The directory one item's files live in.
     *
     * - episode → `Westworld - S01E02 - Chestnut`
     * - track (M13) → `Fleetwood Mac - Rumours - 04 - Go Your Own Way`
     * - anything else → `Arrival (2016)`, dropping the parenthesis when the year is unknown
     *
     * A track's form mirrors the episode's, and for the same two reasons. It is what someone
     * plugging the tablet into a computer can read; and it is **unique**, which the plain-name form
     * is not for music — a track has no `productionYear` to disambiguate it, so two different
     * albums' *Intro* would share one directory, share one `primary.webp`, and have either's delete
     * take the other's files with it. Artist and album in front of the track number make that
     * collision as unlikely as the series name makes it for an episode.
     *
     * @param fallbackId appended when the resulting name would be empty, so two nameless items
     *   cannot collide into the same directory.
     */
    fun itemDirectoryName(
        item: BaseItemDto,
        fallbackId: String = item.id.toString(),
    ): String {
        val raw =
            when (item.type) {
                BaseItemKind.EPISODE ->
                    listOfNotNull(
                        item.seriesName?.takeIf { it.isNotBlank() },
                        episodeCode(item.parentIndexNumber, item.indexNumber),
                        item.name?.takeIf { it.isNotBlank() },
                    ).joinToString(" - ")

                BaseItemKind.AUDIO ->
                    listOfNotNull(
                        item.albumArtist?.takeIf { it.isNotBlank() },
                        item.album?.takeIf { it.isNotBlank() },
                        trackCode(item.indexNumber),
                        item.name?.takeIf { it.isNotBlank() },
                    ).joinToString(" - ")

                else -> {
                    val name = item.name.orEmpty()
                    val year = item.productionYear
                    if (year != null && name.isNotBlank()) "$name ($year)" else name
                }
            }

        return sanitize(raw).ifBlank { "$FALLBACK-$fallbackId" }
    }

    /**
     * The media file's name.
     *
     * The server's own filename is preferred — it carries the release/edition information a user
     * recognises, and keeping it means the file on disk matches the one on the server by name as
     * well as by bytes. When the item carries no path (the `PATH` field is only returned to users
     * allowed to see it) the container extension is enough for ExoPlayer to sniff the format.
     *
     * A transcoded download (M9) is none of that: the source's name and container describe a file
     * this device is not going to receive, so it is named after its directory and given the
     * container the transcode actually produces. The quality is part of the name because it is the
     * one thing a user cannot see from the outside, and because it keeps a re-download at a
     * different quality from silently landing on top of the old file.
     */
    fun mediaFileName(
        item: BaseItemDto,
        directoryName: String,
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
    ): String {
        if (quality.isTranscoded) {
            val suffix = quality.name.lowercase(Locale.ROOT)
            return "$directoryName ($suffix).${DownloadQuality.CONTAINER}"
        }

        val serverName =
            item.path
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() }
        if (serverName != null) {
            return sanitize(serverName).ifBlank { "$FALLBACK.$DEFAULT_CONTAINER" }
        }

        val container =
            item.container
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_CONTAINER
        return "$directoryName.$container"
    }

    /**
     * `04` for a numbered track, `null` for one the server gives no track number.
     *
     * Deliberately not disc-qualified: a multi-disc album repeats track numbers across its discs,
     * but the album *name* is already in the directory name and the track title follows the number,
     * so the pair still reads unambiguously — and a bare `04` is what a music file is named
     * everywhere else the user has seen one.
     */
    fun trackCode(trackNumber: Int?): String? = trackNumber?.let { String.format(Locale.ROOT, "%02d", it) }

    /** `S01E02`, or `E02` when the season number is unknown, or `null` when neither is known. */
    fun episodeCode(
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): String? =
        when {
            seasonNumber != null && episodeNumber != null ->
                String.format(Locale.ROOT, "S%02dE%02d", seasonNumber, episodeNumber)

            episodeNumber != null -> String.format(Locale.ROOT, "E%02d", episodeNumber)
            else -> null
        }

    /**
     * Makes [raw] safe to use as one path segment: illegal characters become spaces, whitespace
     * collapses, and a trailing dot (which Windows and some SMB shares silently strip) is removed.
     */
    fun sanitize(raw: String): String =
        raw
            .replace(ILLEGAL, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .trimEnd('.')
            .trim()
            .take(MAX_SEGMENT)
            .trim()
}
