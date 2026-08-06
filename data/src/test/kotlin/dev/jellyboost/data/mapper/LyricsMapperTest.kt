package dev.jellyboost.data.mapper

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.LyricLine
import org.jellyfin.sdk.model.api.LyricMetadata
import org.junit.jupiter.api.Test

/** Unit tests for [LyricDto.toDomain]. */
class LyricsMapperTest {
    @Test
    fun `lines carry their text and start ticks straight through`() {
        val dto =
            LyricDto(
                metadata = LyricMetadata(isSynced = true),
                lyrics =
                    listOf(
                        LyricLine(text = "Fake plastic trees", start = 10_000_000L),
                        LyricLine(text = "And she lives with a broken man", start = 20_000_000L),
                    ),
            )

        val lyrics = dto.toDomain()

        lyrics.lines.map { it.text } shouldContainExactly
            listOf("Fake plastic trees", "And she lives with a broken man")
        lyrics.lines.map { it.startTicks } shouldContainExactly listOf(10_000_000L, 20_000_000L)
    }

    @Test
    fun `the metadata isSynced flag is trusted when the source set it`() {
        val dto =
            LyricDto(
                metadata = LyricMetadata(isSynced = false),
                // Every line carries timing, but the source explicitly said this is not synced —
                // that call is respected rather than second-guessed from the lines.
                lyrics = listOf(LyricLine(text = "La la la", start = 0L)),
            )

        dto.toDomain().isSynced shouldBe false
    }

    @Test
    fun `sync is inferred from the lines when the metadata flag is unset`() {
        val withTiming =
            LyricDto(
                metadata = LyricMetadata(isSynced = null),
                lyrics = listOf(LyricLine(text = "Timed line", start = 5_000_000L)),
            )
        val withoutTiming =
            LyricDto(
                metadata = LyricMetadata(isSynced = null),
                lyrics = listOf(LyricLine(text = "Plain line", start = null)),
            )

        withTiming.toDomain().isSynced shouldBe true
        withoutTiming.toDomain().isSynced shouldBe false
    }

    @Test
    fun `no lines maps to an empty, unsynced Lyrics`() {
        val dto = LyricDto(metadata = LyricMetadata(isSynced = null), lyrics = emptyList())

        val lyrics = dto.toDomain()

        lyrics.lines.shouldBeEmpty()
        lyrics.isSynced shouldBe false
    }
}
