package dev.jellyboost.player.ui

import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.jupiter.api.Test

/**
 * Unit tests for [withoutAccessToken] — the Coil cache key `TrickplayPreview` uses instead of the
 * raw, tokened tile URL.
 */
class TrickplayPreviewTest {
    @Test
    fun `strips the access token query parameter`() {
        val url = "https://server/Videos/1/Trickplay/320/0.jpg?${ApiClient.QUERY_ACCESS_TOKEN}=secret-token"

        url.withoutAccessToken() shouldBe "https://server/Videos/1/Trickplay/320/0.jpg"
    }

    @Test
    fun `keeps any other query parameter the token sat alongside`() {
        val url = "https://server/tile.jpg?width=320&${ApiClient.QUERY_ACCESS_TOKEN}=secret-token&extra=1"

        url.withoutAccessToken() shouldBe "https://server/tile.jpg?width=320&extra=1"
    }

    @Test
    fun `a url with no query string is unchanged`() {
        val url = "file:///storage/emulated/0/downloads/1/trickplay/0.jpg"

        url.withoutAccessToken() shouldBe url
    }

    @Test
    fun `a url whose only query parameter is the token drops the question mark entirely`() {
        val url = "https://server/tile.jpg?${ApiClient.QUERY_ACCESS_TOKEN}=secret-token"

        url.withoutAccessToken() shouldBe "https://server/tile.jpg"
    }

    @Test
    fun `rotating the token yields the same key`() {
        val before = "https://server/tile.jpg?${ApiClient.QUERY_ACCESS_TOKEN}=old-token"
        val after = "https://server/tile.jpg?${ApiClient.QUERY_ACCESS_TOKEN}=new-token"

        // The whole point: a re-login must not orphan every tile this item had cached.
        after.withoutAccessToken() shouldBe before.withoutAccessToken()
    }
}
