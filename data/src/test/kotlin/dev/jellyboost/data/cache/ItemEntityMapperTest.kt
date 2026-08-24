package dev.jellyboost.data.cache

import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.data.cache.CacheFixtures.MOVIES_LIBRARY
import dev.jellyboost.data.cache.CacheFixtures.NOW
import dev.jellyboost.data.cache.CacheFixtures.entity
import dev.jellyboost.data.cache.CacheFixtures.episodeDto
import dev.jellyboost.data.cache.CacheFixtures.mapper
import dev.jellyboost.data.cache.CacheFixtures.movieDto
import dev.jellyboost.data.cache.CacheFixtures.userData
import dev.jellyboost.data.cache.CacheFixtures.uuid
import dev.jellyboost.data.mapper.FakeImageUrlFactory
import dev.jellyboost.data.mapper.ItemMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [ItemEntityMapper] — the `BaseItemDto` ⇄ Room boundary.
 *
 * The round trip is the load-bearing assertion: the plan's promise is that a cached item and a
 * freshly fetched one are *the same* domain object, because the same [dev.jellyboost.data.mapper.ItemMapper]
 * produces both. Anything the blob loses would show up as a subtly different offline UI.
 */
class ItemEntityMapperTest {
    private val movieId = uuid(1)

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    fun `a cached item comes back as the same domain item`() {
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                name = "Arrival",
                overview = "Linguist meets heptapods.",
                productionYear = 2016,
                communityRating = 7.9f,
                officialRating = "PG-13",
                genres = listOf("Science Fiction", "Drama"),
                runTimeTicks = 70_920_000_000L,
                taglines = listOf("Why are they here?"),
                studios = listOf(NameGuidPair(name = "Paramount", id = uuid(2))),
                imageTags = mapOf(ImageType.PRIMARY to "primary-tag"),
                primaryImageAspectRatio = 0.666,
            )

        val restored = mapper.toDomainOrNull(mapper.toEntity(dto, ItemSource.BROWSE_CACHE, NOW))

        restored.shouldNotBeNull()
        // The promise the whole offline path rests on: byte-identical to what the online path
        // would have produced from the very same DTO.
        restored shouldBe ItemMapper(FakeImageUrlFactory()).toDomain(dto)
        restored.name shouldBe "Arrival"
        restored.overview shouldBe "Linguist meets heptapods."
        restored.genres shouldContainExactly listOf("Science Fiction", "Drama")
        restored.taglines shouldContainExactly listOf("Why are they here?")
        restored.studios shouldContainExactly listOf("Paramount")
        restored.communityRating shouldBe 7.9f
        restored.primaryImageUrl!! shouldContain "primary-tag"
        restored.primaryImageAspectRatio shouldBe 0.666
    }

    @Test
    fun `an episode keeps everything the offline home rows group by`() {
        val dto =
            episodeDto(
                id = uuid(3),
                name = "The Pointy End",
                seriesId = uuid(4),
                seriesName = "Game of Thrones",
                seasonId = uuid(5),
                seasonNumber = 1,
                episodeNumber = 8,
            )

        val row = mapper.toEntity(dto, ItemSource.DOWNLOAD, NOW)

        row.type shouldBe ItemType.EPISODE
        row.seriesId shouldBe uuid(4)
        row.seasonId shouldBe uuid(5)
        row.parentIndexNumber shouldBe 1
        row.indexNumber shouldBe 8
        row.seriesName shouldBe "Game of Thrones"
    }

    // ---- music query columns ---------------------------------------------------------------------

    @Test
    fun `stores a track's album and album-artist ids as query-only columns`() {
        val albumId = uuid(30)
        val albumArtistId = uuid(31)
        val dto =
            BaseItemDto(
                id = uuid(32),
                type = BaseItemKind.AUDIO,
                name = "Comfortably Numb",
                albumId = albumId,
                albumArtists = listOf(NameGuidPair(name = "Pink Floyd", id = albumArtistId)),
            )

        val row = mapper.toEntity(dto, ItemSource.DOWNLOAD, NOW)

        row.albumId shouldBe albumId
        row.albumArtistId shouldBe albumArtistId
    }

    @Test
    fun `leaves the music query columns null for a non-music item`() {
        val row = mapper.toEntity(movieDto(movieId, "Arrival"), ItemSource.DOWNLOAD, NOW)

        row.albumId.shouldBeNull()
        row.albumArtistId.shouldBeNull()
    }

    @Test
    fun `a restored track carries the same album fields the online mapper would produce`() {
        val albumId = uuid(33)
        val albumArtistId = uuid(34)
        val dto =
            BaseItemDto(
                id = uuid(35),
                type = BaseItemKind.AUDIO,
                name = "Comfortably Numb",
                album = "The Wall",
                albumId = albumId,
                albumArtist = "Pink Floyd",
                artists = listOf("Pink Floyd"),
                albumArtists = listOf(NameGuidPair(name = "Pink Floyd", id = albumArtistId)),
            )

        val restored = mapper.toDomainOrNull(mapper.toEntity(dto, ItemSource.DOWNLOAD, NOW))

        restored.shouldNotBeNull()
        restored shouldBe ItemMapper(FakeImageUrlFactory()).toDomain(dto)
        restored.album shouldBe "The Wall"
        restored.albumId shouldBe albumId.toString()
        restored.albumArtist shouldBe "Pink Floyd"
    }

    // ---- structured columns -------------------------------------------------------------------

    @Test
    fun `uses the server's sort name when it has one`() {
        val row = mapper.toEntity(movieDto(movieId, "The Matrix", sortName = "Matrix, The"), ItemSource.DOWNLOAD, NOW)

        row.sortName shouldBe "Matrix, The"
    }

    @Test
    fun `falls back to the display name when the server has no sort name`() {
        val row = mapper.toEntity(movieDto(movieId, "Arrival", sortName = null), ItemSource.DOWNLOAD, NOW)

        row.sortName shouldBe "Arrival"
    }

    @Test
    fun `treats a blank sort name as absent`() {
        val row = mapper.toEntity(movieDto(movieId, "Arrival", sortName = "   "), ItemSource.DOWNLOAD, NOW)

        row.sortName shouldBe "Arrival"
    }

    @Test
    fun `records the source and cache timestamp it was given`() {
        val cachedAt = Instant.parse("2026-01-01T00:00:00Z")

        val row = mapper.toEntity(movieDto(movieId, "Arrival"), ItemSource.DOWNLOAD, cachedAt)

        row.source shouldBe ItemSource.DOWNLOAD
        row.cachedAt shouldBe cachedAt
        row.parentId shouldBe MOVIES_LIBRARY
    }

    // ---- local user data ----------------------------------------------------------------------

    @Test
    fun `overlays this device's playback state onto the cached item`() {
        val row = entity(movieDto(movieId, "Arrival"))

        val restored =
            mapper.toDomainOrNull(row, userData(movieId, positionTicks = 12_000_000_000L))

        restored.shouldNotBeNull()
        restored.userData.playbackPositionTicks shouldBe 12_000_000_000L
        restored.userData.isResumable shouldBe true
    }

    @Test
    fun `leaves the cached user data alone when this device has none`() {
        val restored = mapper.toDomainOrNull(entity(movieDto(movieId, "Arrival")), userData = null)

        restored.shouldNotBeNull()
        restored.userData.playbackPositionTicks shouldBe 0L
    }

    // ---- the synthesised series card ------------------------------------------------------------

    @Test
    fun `rebuilds the series an episode belongs to, with the show's own poster`() {
        val row =
            entity(
                episodeDto(
                    id = uuid(12),
                    name = "Winter Is Coming",
                    seriesId = uuid(10),
                    seriesName = "Thrones",
                    seasonId = uuid(11),
                    seasonNumber = 1,
                    episodeNumber = 1,
                    seriesPrimaryImageTag = "series-tag",
                ),
            )

        val card = mapper.toSeriesCardOrNull(row)

        card.shouldNotBeNull()
        // The tap target has to be the show, not the episode the card was built from.
        card.id shouldBe uuid(10).toString()
        card.name shouldBe "Thrones"
        card.type shouldBe ItemType.SERIES
        card.primaryImageUrl!! shouldContain "/Items/${uuid(10)}/Images/PRIMARY?tag=series-tag"
    }

    @Test
    fun `a series card carries no artwork when the episode names no series image`() {
        val row = entity(episodeDto(uuid(12), "Winter Is Coming", uuid(10), "Thrones", uuid(11), 1, 1))

        mapper.toSeriesCardOrNull(row)!!.primaryImageUrl.shouldBeNull()
    }

    @Test
    fun `there is no series card for a row that names no series`() {
        mapper.toSeriesCardOrNull(entity(movieDto(movieId, "Arrival"))).shouldBeNull()
    }

    @Test
    fun `there is no series card for a row whose series has no name`() {
        val nameless =
            entity(
                episodeDto(uuid(12), "Winter Is Coming", uuid(10), "Thrones", uuid(11), 1, 1)
                    .copy(seriesName = "  "),
            )

        mapper.toSeriesCardOrNull(nameless).shouldBeNull()
    }

    @Test
    fun `there is no series card for an unreadable blob`() {
        val corrupted =
            entity(episodeDto(uuid(12), "Winter Is Coming", uuid(10), "Thrones", uuid(11), 1, 1))
                .copy(dto = "not json")

        mapper.toSeriesCardOrNull(corrupted).shouldBeNull()
    }

    // ---- failure modes ------------------------------------------------------------------------

    @Test
    fun `treats an unreadable blob as not cached rather than throwing`() {
        val corrupted = entity(movieDto(movieId, "Arrival")).copy(dto = "{ this is not json")

        mapper.toDomainOrNull(corrupted).shouldBeNull()
    }

    @Test
    fun `drops unreadable rows from a page instead of failing the whole page`() {
        val good = entity(movieDto(uuid(10), "Arrival"))
        val bad = entity(movieDto(uuid(11), "Broken")).copy(dto = "nonsense")

        mapper.toDomain(listOf(good, bad)).map { it.id } shouldContainExactly listOf(uuid(10).toString())
    }

    // ---- library views ------------------------------------------------------------------------

    @Test
    fun `caches a supported library with its server order`() {
        val dto =
            BaseItemDto(
                id = MOVIES_LIBRARY,
                type = BaseItemKind.COLLECTION_FOLDER,
                name = "Films",
                collectionType = org.jellyfin.sdk.model.api.CollectionType.MOVIES,
                imageTags = mapOf(ImageType.PRIMARY to "library-tag"),
                // The folder-children count the server sends; the cache stores no count at all.
                childCount = 3,
            )

        val row = mapper.toEntity(dto, sortIndex = 3, cachedAt = NOW)

        row.shouldNotBeNull()
        row.name shouldBe "Films"
        row.collectionType shouldBe CollectionKind.MOVIES.name
        row.sortIndex shouldBe 3
        row.primaryImageTag shouldBe "library-tag"

        val restored = mapper.toDomain(row)
        restored.name shouldBe "Films"
        restored.collectionType shouldBe CollectionKind.MOVIES
        restored.primaryImageUrl!! shouldContain "library-tag"
        // Offline tiles draw their name alone: Room has no column for a count, and a cache holding
        // only downloaded items could not answer one honestly.
        restored.itemCount.shouldBeNull()
    }

    @Test
    fun `refuses to cache a library kind the app does not support`() {
        // Music is part of SUPPORTED — photos is what stays outside it, so this is still a live
        // case for the guard being tested.
        val dto =
            BaseItemDto(
                id = uuid(20),
                type = BaseItemKind.COLLECTION_FOLDER,
                name = "Photos",
                collectionType = org.jellyfin.sdk.model.api.CollectionType.PHOTOS,
            )

        mapper.toEntity(dto, sortIndex = 0, cachedAt = NOW).shouldBeNull()
    }
}
