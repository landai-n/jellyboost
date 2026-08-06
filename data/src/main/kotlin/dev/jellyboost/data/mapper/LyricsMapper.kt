package dev.jellyboost.data.mapper

import dev.jellyboost.core.common.music.LyricLine
import dev.jellyboost.core.common.music.Lyrics
import org.jellyfin.sdk.model.api.LyricDto

/**
 * [LyricDto] → [Lyrics] (M13 Phase 6, docs/notes/music-m13-plan.md, key decision 11).
 *
 * `LyricDto.metadata` is non-null on the wire (verified against the SDK 1.8.12 model jar) and
 * carries its own nullable `isSynced` flag; that flag is trusted first, and only when the source
 * left it unset does this fall back to inferring sync from the lines themselves — a lyric file with
 * even one timed line is synced enough to drive the highlight, and a source that put timing on
 * every line but forgot the flag should not be demoted to static text.
 */
internal fun LyricDto.toDomain(): Lyrics {
    val domainLines = lyrics.map { line -> LyricLine(startTicks = line.start, text = line.text) }
    val isSynced = metadata.isSynced ?: domainLines.any { it.startTicks != null }
    return Lyrics(lines = domainLines, isSynced = isSynced)
}
