package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.model.DownloadQuality
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale

/**
 * Naming rules for what lands on disk: `Series - S01E02 - Title/` or `Movie (Year)/`. The names are
 * user-visible — someone plugging the tablet into a computer can tell what the folders are — so they
 * are built from metadata rather than ids, then made safe for the FAT/exFAT volume an SD card is.
 */
internal object DownloadPaths {
    /**
     * `:` and `?` are legal on ext4 but not on the exFAT volumes SD cards ship with, so a film called
     * *Mission: Impossible* would fail to create its directory on some devices only. Hyphens are
     * deliberately **kept**: the directory format uses ` - ` as its separator.
     */
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    /** Runs of whitespace collapse to one space so a trimmed title does not keep a double gap. */
    private val WHITESPACE = Regex("\\s+")

    /** Well under the 255-byte limit every relevant filesystem imposes, with room for multi-byte titles. */
    private const val MAX_SEGMENT = 120

    /** Used when an item has no usable name at all — never expected, never allowed to crash. */
    private const val FALLBACK = "download"

    /** Extension assumed when the server tells us nothing about the container. */
    private const val DEFAULT_CONTAINER = "mkv"

    /**
     * The directory one item's files live in: `Westworld - S01E02 - Chestnut`,
     * `Fleetwood Mac - Rumours - 04 - Go Your Own Way` (`2-04` on disc 2), or `Arrival (2016)`.
     *
     * A track's form mirrors the episode's because it has to be **unique**: a track has no
     * `productionYear` to disambiguate it, so two different albums' *Intro* would share one directory,
     * share one `primary.webp`, and have either's delete take the other's files. A track with neither
     * artist nor album takes the same id suffix the empty-name fallback uses.
     *
     * @param fallbackId appended when the resulting name would be empty, so two nameless items cannot
     *   collide into the same directory.
     */
    fun itemDirectoryName(
        item: BaseItemDto,
        fallbackId: String = item.id.toString(),
    ): String {
        var needsIdSuffix = false
        val raw =
            when (item.type) {
                BaseItemKind.EPISODE ->
                    listOfNotNull(
                        item.seriesName?.takeIf { it.isNotBlank() },
                        episodeCode(item.parentIndexNumber, item.indexNumber),
                        item.name?.takeIf { it.isNotBlank() },
                    ).joinToString(" - ")

                BaseItemKind.AUDIO -> {
                    val artist = item.albumArtist?.takeIf { it.isNotBlank() }
                    val album = item.album?.takeIf { it.isNotBlank() }
                    needsIdSuffix = artist == null && album == null
                    listOfNotNull(
                        artist,
                        album,
                        trackCode(item.parentIndexNumber, item.indexNumber),
                        item.name?.takeIf { it.isNotBlank() },
                    ).joinToString(" - ")
                }

                else -> {
                    val name = item.name.orEmpty()
                    val year = item.productionYear
                    if (year != null && name.isNotBlank()) "$name ($year)" else name
                }
            }

        val sanitized = sanitize(raw)
        return when {
            sanitized.isBlank() -> "$FALLBACK-$fallbackId"
            needsIdSuffix -> "$sanitized-$fallbackId"
            else -> sanitized
        }
    }

    /**
     * The media file's name. The server's own filename is preferred, so the file on disk matches the
     * one on the server by name as well as by bytes; `PATH` is only returned to users allowed to see
     * it, and without it the container extension is enough for ExoPlayer to sniff the format.
     *
     * A transcode is named after its directory instead — the source's name and container describe a
     * file this device is not going to receive — and carries its quality, so a re-download at another
     * step cannot land silently on top of the old file.
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
     * `04` for a numbered track, `2-04` for track 4 on disc 2, `null` when the server gives no track
     * number. Disc-qualified because a multi-disc album repeats track numbers across its discs, and two
     * discs' fourth tracks *sharing a title* would otherwise sanitise to the same directory.
     */
    fun trackCode(
        discNumber: Int?,
        trackNumber: Int?,
    ): String? =
        when {
            trackNumber == null -> null
            discNumber != null -> String.format(Locale.ROOT, "%d-%02d", discNumber, trackNumber)
            else -> String.format(Locale.ROOT, "%02d", trackNumber)
        }

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
