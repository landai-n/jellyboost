package dev.jellyboost.player.cast

import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What `CastPlayerHandle` says about a receiver letting go of the item — the edges only. */
class RemoteItemPresenceTest {
    private val emitted = mutableListOf<PlayerEvent>()
    private val presence = RemoteItemPresence(emit = { emitted += it })

    private val playing = PlaybackSnapshot(positionMs = 600_000L, durationMs = 7_200_000L, isPlaying = true)
    private val notOurs = PlaybackSnapshot(isValid = false)

    @Test
    fun `the seconds after a load are not a drop, however long they last`() {
        presence.onLoad()

        // The receiver has not reported the new item yet: every reading is someone else's.
        repeat(100) { presence.onReading(notOurs, ready = false) }

        emitted.shouldBeEmpty()
    }

    @Test
    fun `an item only glimpsed while loading does not arm it`() {
        // A placeholder can briefly claim the item before the receiver has it; that is not "held".
        presence.onLoad()
        presence.onReading(PlaybackSnapshot(positionMs = 0L), ready = false)

        presence.onReading(notOurs, ready = false)

        emitted.shouldBeEmpty()
    }

    @Test
    fun `an item the receiver held and then let go of is reported once, with where it was`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)

        presence.onReading(notOurs, ready = false)
        presence.onReading(notOurs, ready = false)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(playing))
    }

    @Test
    fun `the position it resumes from is the last one held, not the one it was armed at`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        val later = playing.copy(positionMs = 660_000L)
        presence.onReading(later, ready = false)

        presence.onReading(notOurs, ready = false)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(later))
    }

    @Test
    fun `an item that comes back clears it, once`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        presence.onReading(notOurs, ready = false)

        presence.onReading(playing, ready = true)
        presence.onReading(playing, ready = true)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(playing), PlayerEvent.RemoteItemMissingCleared)
    }

    @Test
    fun `a new load clears it and disarms, so its own loading window is not a drop`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        presence.onReading(notOurs, ready = false)

        presence.onLoad()
        presence.onReading(notOurs, ready = false)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(playing), PlayerEvent.RemoteItemMissingCleared)
    }

    // ---- a finish is an end, not a drop -----------------------------------------------------------------

    private val finishedAtTheEnd = playing.copy(positionMs = 7_200_000L, isPlaying = false, hasEnded = true)

    @Test
    fun `the held item finishing is an end, at its duration, said once`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)

        val first = presence.onFinished(ReceiverFinish.LOADED_ITEM)
        val again = presence.onFinished(ReceiverFinish.LOADED_ITEM)

        first shouldBe finishedAtTheEnd
        again shouldBe finishedAtTheEnd
        presence.ended shouldBe finishedAtTheEnd
        emitted shouldBe listOf(PlayerEvent.Ended)
    }

    @Test
    fun `a finish status that names nothing is the held item's while it was still held`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)

        presence.onFinished(ReceiverFinish.UNNAMED) shouldBe finishedAtTheEnd

        emitted shouldBe listOf(PlayerEvent.Ended)
    }

    @Test
    fun `an ended item is never reported missing afterwards`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        val ended = presence.onFinished(ReceiverFinish.LOADED_ITEM)

        // What the handle feeds it from then on: the ended reading, not the receiver's empty one.
        repeat(3) { presence.onReading(requireNotNull(ended), ready = true) }

        emitted shouldBe listOf(PlayerEvent.Ended)
    }

    @Test
    fun `a drop noticed just before the finish status is cleared, then ended`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        presence.onReading(notOurs, ready = false)

        presence.onFinished(ReceiverFinish.LOADED_ITEM)

        emitted shouldBe
            listOf(PlayerEvent.RemoteItemMissing(playing), PlayerEvent.RemoteItemMissingCleared, PlayerEvent.Ended)
    }

    @Test
    fun `the film before's finish, still on the receiver as the next loads, is not the next one's end`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        presence.onFinished(ReceiverFinish.LOADED_ITEM)
        emitted.clear()

        presence.onLoad()
        presence.onReading(PlaybackSnapshot(positionMs = 0L), ready = false)

        presence.onFinished(ReceiverFinish.LOADED_ITEM).shouldBeNull()
        presence.onFinished(ReceiverFinish.UNNAMED).shouldBeNull()
        presence.ended.shouldBeNull()
        emitted.shouldBeEmpty()
    }

    @Test
    fun `another sender's media finishing is not ours`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)

        presence.onFinished(ReceiverFinish.OTHER_ITEM).shouldBeNull()

        emitted.shouldBeEmpty()
    }

    @Test
    fun `an unnamed finish after the item was already dropped is not ours`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        presence.onReading(notOurs, ready = false)

        presence.onFinished(ReceiverFinish.UNNAMED).shouldBeNull()

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(playing))
    }

    @Test
    fun `a load with nothing missing says nothing`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)

        presence.onLoad()

        emitted.shouldBeEmpty()
    }
}
