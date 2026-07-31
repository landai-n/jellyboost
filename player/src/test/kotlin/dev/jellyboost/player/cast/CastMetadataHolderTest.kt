package dev.jellyboost.player.cast

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CastMetadataHolder].
 *
 * One rule, and it is about what the television must never say: the metadata handed to a load is the
 * metadata published for *that* item, or none at all. A holder that answered with whatever it last
 * held would caption the second episode of an evening with the first one's title — visible only on a
 * screen the user cannot correct from here, and outlasting the session.
 */
class CastMetadataHolderTest {
    private val holder = CastMetadataHolder()

    @Test
    fun `an item is announced as the screen named it`() {
        holder.publish(ITEM, ARRIVAL)

        holder.metadataFor(ITEM) shouldBe ARRIVAL
    }

    @Test
    fun `nothing has been published yet, so nothing is announced`() {
        holder.metadataFor(ITEM) shouldBe CastMetadata()
    }

    @Test
    fun `another item's metadata is not borrowed`() {
        holder.publish(ITEM, ARRIVAL)

        // The receiver falls back to its own idle backdrop, which is a cosmetic loss; the wrong
        // title is a lie.
        holder.metadataFor(OTHER_ITEM) shouldBe CastMetadata()
    }

    @Test
    fun `the queue moves on, and so does what the television is told`() {
        holder.publish(ITEM, ARRIVAL)
        holder.publish(OTHER_ITEM, EPISODE)

        holder.metadataFor(OTHER_ITEM) shouldBe EPISODE
        holder.metadataFor(ITEM) shouldBe CastMetadata()
    }

    @Test
    fun `the same UUID spelled differently is the same UUID`() {
        // The id is a navigation argument on one side and `UUID.toString()` of the resolved source
        // on the other; a difference of case between the two must not cost the film its title.
        holder.publish(ITEM.uppercase(), ARRIVAL)

        holder.metadataFor(ITEM) shouldBe ARRIVAL
    }

    private companion object {
        const val ITEM = "4b2c1a3d-0000-4000-8000-000000000001"
        const val OTHER_ITEM = "4b2c1a3d-0000-4000-8000-000000000002"

        val ARRIVAL = CastMetadata(title = "Arrival", subtitle = "2016", posterUrl = "https://server/p.jpg")
        val EPISODE = CastMetadata(title = "The Bells", subtitle = "S08E05")
    }
}
