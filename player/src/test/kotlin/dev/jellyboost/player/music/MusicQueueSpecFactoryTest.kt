package dev.jellyboost.player.music

import dev.jellyboost.player.PlayMethod
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// The metadata built here is the *only* source the media notification and lock screen have (the
// session reads it straight off the timeline), so getting it wrong is a wrong lock screen and
// nothing else in the app would say so.
class MusicQueueSpecFactoryTest {
    private val factory = MusicQueueSpecFactory()

    private fun stream(itemIndex: Int = 0) =
        MusicStream(
            itemId = MusicFixtures.TRACK_IDS[itemIndex],
            uri = "https://server/Audio/x/universal",
            playSessionId = "session-$itemIndex",
            playMethod = PlayMethod.DIRECT_PLAY,
            mediaSourceId = "source-$itemIndex",
            runTimeTicks = MusicFixtures.RUN_TIME_TICKS,
        )

    @Test
    fun `the entry is keyed on the item id and carries what the notification draws`() {
        val item = MusicFixtures.track(0)

        val entry = factory.create(item, stream())

        entry.mediaId shouldBe item.id
        entry.title shouldBe "Track 1"
        entry.albumTitle shouldBe "Isla"
        entry.artworkUri shouldBe item.primaryImageUrl
        entry.trackNumber shouldBe 1
        entry.discNumber shouldBe 1
    }

    @Test
    fun `every credited performer reaches the artist line`() {
        val item = MusicFixtures.track(0, artists = listOf("Portico Quartet", "Cornelia"))

        factory.create(item, stream()).artist shouldBe "Portico Quartet, Cornelia"
    }

    @Test
    fun `a track crediting nobody falls back to the album artist`() {
        val item = MusicFixtures.track(0, artists = emptyList())

        factory.create(item, stream()).artist shouldBe "Portico Quartet"
    }

    @Test
    fun `the reporting terms travel with the entry`() {
        val entry = factory.create(MusicFixtures.track(1), stream(1))

        entry.itemId shouldBe MusicFixtures.TRACK_IDS[1]
        entry.playSessionId shouldBe "session-1"
        entry.mediaSourceId shouldBe "source-1"
        entry.playMethod shouldBe PlayMethod.DIRECT_PLAY
        entry.runTimeTicks shouldBe MusicFixtures.RUN_TIME_TICKS
    }
}
