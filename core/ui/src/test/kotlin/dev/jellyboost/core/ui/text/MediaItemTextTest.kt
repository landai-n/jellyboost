package dev.jellyboost.core.ui.text

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MediaItemTextTest {
    @Test
    fun `an episode carries its season and episode numbers`() {
        val numbering = item(ItemType.EPISODE, season = 1, index = 14).episodeNumbering()

        numbering shouldBe (1 to 14)
    }

    @Test
    fun `an episode with no season number still carries its own`() {
        item(ItemType.EPISODE, season = null, index = 4).episodeNumbering() shouldBe (null to 4)
    }

    @Test
    fun `an episode without a number has no numbering at all`() {
        item(ItemType.EPISODE, season = 1, index = null).episodeNumbering().shouldBeNull()
    }

    /**
     * A track's index is its position on a disc and its parent index the disc number, so an
     * ungated reading of the two labels a track on a video surface "S1 · E14".
     */
    @Test
    fun `a track is never labelled by episode, whatever its disc and track numbers are`() {
        item(ItemType.AUDIO, season = 1, index = 14).episodeNumbering().shouldBeNull()
    }

    @Test
    fun `no other kind grows an episode label either`() {
        item(ItemType.MOVIE, season = null, index = 2).episodeNumbering().shouldBeNull()
        item(ItemType.MUSIC_ALBUM, season = 1, index = 1).episodeNumbering().shouldBeNull()
        item(ItemType.SEASON, season = null, index = 1).episodeNumbering().shouldBeNull()
        item(ItemType.UNKNOWN, season = 1, index = 1).episodeNumbering().shouldBeNull()
    }

    private fun item(
        type: ItemType,
        season: Int?,
        index: Int?,
    ) = JellyfinItem(
        id = "1",
        name = "Fake Plastic Trees",
        type = type,
        indexNumber = index,
        parentIndexNumber = season,
    )
}
