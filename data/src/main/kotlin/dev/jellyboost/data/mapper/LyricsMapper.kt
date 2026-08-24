package dev.jellyboost.data.mapper

import dev.jellyboost.core.common.music.LyricLine
import dev.jellyboost.core.common.music.Lyrics
import org.jellyfin.sdk.model.api.LyricDto

/**
 * `LyricDto.metadata` is non-null on the wire (verified against the SDK 1.8.12 model jar); its
 * nullable `isSynced` flag is trusted first, and sync is inferred from the lines only when the
 * source left it unset — one timed line is enough to drive the highlight.
 */
internal fun LyricDto.toDomain(): Lyrics {
    val domainLines = lyrics.map { line -> LyricLine(startTicks = line.start, text = line.text) }
    val isSynced = metadata.isSynced ?: domainLines.any { it.startTicks != null }
    return Lyrics(lines = domainLines, isSynced = isSynced)
}
