package dev.jellyboost.player.music

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.player.api.AudioStreamRequest
import dev.jellyboost.player.api.AudioStreamUrlFactory
import java.util.UUID

/** Tracks, and a URL factory with no server behind it, shared by the music tests. */
internal object MusicFixtures {
    val TRACK_IDS: List<UUID> =
        listOf(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
        )

    fun track(
        index: Int,
        name: String = "Track ${index + 1}",
        container: String? = "flac",
        artists: List<String> = listOf("Portico Quartet"),
    ): JellyfinItem =
        JellyfinItem(
            id = TRACK_IDS[index].toString(),
            name = name,
            type = ItemType.AUDIO,
            runTimeTicks = RUN_TIME_TICKS,
            indexNumber = index + 1,
            parentIndexNumber = 1,
            album = "Isla",
            albumArtist = "Portico Quartet",
            artists = artists,
            primaryImageUrl = "https://server/Items/${TRACK_IDS[index]}/Images/Primary",
            container = container,
        )

    fun album(size: Int = 3): List<JellyfinItem> = List(size) { track(it) }

    const val RUN_TIME_TICKS = 2_400_000_000L

    /** Deterministic URLs, so a test can assert what the resolver asked for. */
    class FakeAudioStreamUrlFactory : AudioStreamUrlFactory {
        val requests = mutableListOf<String>()

        override fun audioUniversalUrl(request: AudioStreamRequest): String =
            with(request) {
                requests +=
                    "$itemId|${containers.joinToString("+")}|$audioCodec|" +
                    "$transcodingContainer|$maxStreamingBitrate|$audioBitRate"
                "https://server/Audio/$itemId/universal?PlaySessionId=$playSessionId"
            }
    }
}
