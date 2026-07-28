package dev.jellyfinnative.data.mapper

import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.PersonKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import org.jellyfin.sdk.model.api.PersonKind as SdkPersonKind

/** Unit tests for [ItemMapper] — the `BaseItemDto` → domain boundary. */
class ItemMapperTest {
    private val mapper = ItemMapper(FakeImageUrlFactory())

    private val movieId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val seriesId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val parentId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    /**
     * The SDK's date serializer reads and writes `LocalDateTime` in the *device's* zone, so every
     * date assertion here is only meaningful under a zone that is not UTC — pinning one is what
     * makes the M4 timezone regression (dates off by the local offset) visible to these tests.
     */
    @BeforeEach
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(TEST_ZONE))
    }

    @AfterEach
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `maps the scalar fields of a movie`() {
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                name = "Arrival",
                overview = "Linguist meets heptapods.",
                productionYear = 2016,
                runTimeTicks = 6_000_000_000L,
                communityRating = 7.9f,
                officialRating = "PG-13",
                genres = listOf("Drama", "Science Fiction"),
                primaryImageAspectRatio = 0.666,
            )

        val item = mapper.toDomain(dto)

        item.id shouldBe movieId.toString()
        item.name shouldBe "Arrival"
        item.type shouldBe ItemType.MOVIE
        item.overview shouldBe "Linguist meets heptapods."
        item.productionYear shouldBe 2016
        item.runTimeTicks shouldBe 6_000_000_000L
        item.communityRating shouldBe 7.9f
        item.officialRating shouldBe "PG-13"
        item.genres shouldContainExactly listOf("Drama", "Science Fiction")
        item.primaryImageAspectRatio shouldBe 0.666
    }

    @Test
    fun `maps a missing name to an empty string rather than null`() {
        val item = mapper.toDomain(BaseItemDto(id = movieId, type = BaseItemKind.MOVIE))

        item.name shouldBe ""
    }

    @Test
    fun `maps every supported BaseItemKind and folds the rest into UNKNOWN`() {
        fun typeOf(kind: BaseItemKind) = mapper.toDomain(BaseItemDto(id = movieId, type = kind)).type

        typeOf(BaseItemKind.MOVIE) shouldBe ItemType.MOVIE
        typeOf(BaseItemKind.SERIES) shouldBe ItemType.SERIES
        typeOf(BaseItemKind.SEASON) shouldBe ItemType.SEASON
        typeOf(BaseItemKind.EPISODE) shouldBe ItemType.EPISODE
        typeOf(BaseItemKind.COLLECTION_FOLDER) shouldBe ItemType.COLLECTION_FOLDER
        typeOf(BaseItemKind.USER_VIEW) shouldBe ItemType.COLLECTION_FOLDER
        typeOf(BaseItemKind.FOLDER) shouldBe ItemType.FOLDER
        typeOf(BaseItemKind.AUDIO) shouldBe ItemType.UNKNOWN
    }

    @Test
    fun `prefers the item's own primary image`() {
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                imageTags = mapOf(ImageType.PRIMARY to "own-tag"),
                seriesId = seriesId,
                seriesPrimaryImageTag = "series-tag",
            )

        val url = mapper.toDomain(dto).primaryImageUrl

        url!! shouldContain movieId.toString()
        url shouldContain "tag=own-tag"
    }

    @Test
    fun `falls back to the series primary image for an episode without its own`() {
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.EPISODE,
                seriesId = seriesId,
                seriesPrimaryImageTag = "series-tag",
            )

        val url = mapper.toDomain(dto).primaryImageUrl

        url!! shouldContain seriesId.toString()
        url shouldContain "tag=series-tag"
    }

    @Test
    fun `falls back to the parent primary image when there is no series image`() {
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.EPISODE,
                parentPrimaryImageItemId = parentId,
                parentPrimaryImageTag = "parent-tag",
            )

        val url = mapper.toDomain(dto).primaryImageUrl

        url!! shouldContain parentId.toString()
        url shouldContain "tag=parent-tag"
    }

    @Test
    fun `returns null image urls when the server has no artwork at all`() {
        val item = mapper.toDomain(BaseItemDto(id = movieId, type = BaseItemKind.MOVIE))

        item.primaryImageUrl.shouldBeNull()
        item.backdropImageUrl.shouldBeNull()
        item.thumbImageUrl.shouldBeNull()
        item.logoImageUrl.shouldBeNull()
    }

    @Test
    fun `uses the first backdrop tag, then the parent's`() {
        val own =
            mapper.toDomain(
                BaseItemDto(
                    id = movieId,
                    type = BaseItemKind.MOVIE,
                    backdropImageTags = listOf("backdrop-0", "backdrop-1"),
                ),
            )
        own.backdropImageUrl!! shouldContain "tag=backdrop-0"

        val inherited =
            mapper.toDomain(
                BaseItemDto(
                    id = movieId,
                    type = BaseItemKind.EPISODE,
                    parentBackdropItemId = parentId,
                    parentBackdropImageTags = listOf("parent-backdrop"),
                ),
            )
        inherited.backdropImageUrl!! shouldContain parentId.toString()
        inherited.backdropImageUrl!! shouldContain "tag=parent-backdrop"
    }

    @Test
    fun `falls back through thumb, series thumb and parent thumb`() {
        val seriesThumb =
            mapper.toDomain(
                BaseItemDto(
                    id = movieId,
                    type = BaseItemKind.EPISODE,
                    seriesId = seriesId,
                    seriesThumbImageTag = "series-thumb",
                ),
            )
        seriesThumb.thumbImageUrl!! shouldContain seriesId.toString()

        val parentThumb =
            mapper.toDomain(
                BaseItemDto(
                    id = movieId,
                    type = BaseItemKind.EPISODE,
                    parentThumbItemId = parentId,
                    parentThumbImageTag = "parent-thumb",
                ),
            )
        parentThumb.thumbImageUrl!! shouldContain "tag=parent-thumb"
    }

    @Test
    fun `maps user data including the UTC last played date`() {
        val lastPlayed = LocalDateTime.of(2026, 7, 1, 12, 30)
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                userData =
                    UserItemDataDto(
                        playbackPositionTicks = 12_000_000_000L,
                        playCount = 2,
                        isFavorite = true,
                        played = false,
                        playedPercentage = 20.0,
                        lastPlayedDate = lastPlayed,
                        key = "key",
                        itemId = movieId,
                    ),
            )

        val userData = mapper.toDomain(dto).userData

        userData.playbackPositionTicks shouldBe 12_000_000_000L
        userData.playCount shouldBe 2
        userData.isFavorite shouldBe true
        userData.played shouldBe false
        userData.playedPercentage shouldBe 20.0
        userData.lastPlayedDate shouldBe lastPlayed.atZone(ZoneId.of(TEST_ZONE)).toInstant()
        userData.isResumable shouldBe true
    }

    @Test
    fun `substitutes empty user data when the server omits it`() {
        val userData = mapper.toDomain(BaseItemDto(id = movieId, type = BaseItemKind.MOVIE)).userData

        userData.played shouldBe false
        userData.playbackPositionTicks shouldBe 0L
        userData.lastPlayedDate.shouldBeNull()
    }

    @Test
    fun `keeps only movie and tv libraries when mapping user views`() {
        val views =
            listOf(
                library(UUID.randomUUID(), "Movies", CollectionType.MOVIES),
                library(UUID.randomUUID(), "Shows", CollectionType.TVSHOWS),
                library(UUID.randomUUID(), "Music", CollectionType.MUSIC),
                library(UUID.randomUUID(), "Photos", CollectionType.PHOTOS),
                library(UUID.randomUUID(), "Mystery", null),
            )

        val mapped = mapper.toLibraryViews(views)

        mapped.map { it.name } shouldContainExactly listOf("Movies", "Shows")
        mapped.map { it.collectionType } shouldContainExactly
            listOf(CollectionKind.MOVIES, CollectionKind.TVSHOWS)
    }

    @Test
    fun `maps a list preserving server order`() {
        val ids = List(3) { UUID.randomUUID() }
        val dtos = ids.map { BaseItemDto(id = it, type = BaseItemKind.MOVIE) }

        mapper.toDomain(dtos).map { it.id } shouldContainExactly ids.map { it.toString() }
    }

    // ---- M4: detail-only fields ---------------------------------------------------------------

    @Test
    fun `maps the detail-only fields a full item carries`() {
        val premiere = LocalDateTime.of(2016, 11, 11, 0, 0)
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                name = "Arrival",
                taglines = listOf("Why are they here?"),
                childCount = 4,
                premiereDate = premiere,
                studios = listOf(NameGuidPair(name = "Paramount", id = UUID.randomUUID())),
            )

        val item = mapper.toDomain(dto)

        item.taglines shouldContainExactly listOf("Why are they here?")
        item.childCount shouldBe 4
        item.premiereDate shouldBe premiere.atZone(ZoneId.of(TEST_ZONE)).toInstant()
        item.studios shouldContainExactly listOf("Paramount")
    }

    @Test
    fun `leaves the detail fields empty for a lean list item`() {
        val item = mapper.toDomain(BaseItemDto(id = movieId, type = BaseItemKind.MOVIE))

        item.taglines.shouldBeEmpty()
        item.studios.shouldBeEmpty()
        item.people.shouldBeEmpty()
        item.childCount.shouldBeNull()
        item.premiereDate.shouldBeNull()
    }

    @Test
    fun `maps credits, including the character an actor plays and their headshot`() {
        val actorId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                people =
                    listOf(
                        BaseItemPerson(
                            id = actorId,
                            name = "Amy Adams",
                            role = "Louise Banks",
                            type = SdkPersonKind.ACTOR,
                            primaryImageTag = "headshot",
                        ),
                        BaseItemPerson(
                            id = UUID.randomUUID(),
                            name = "Denis Villeneuve",
                            type = SdkPersonKind.DIRECTOR,
                        ),
                    ),
            )

        val people = mapper.toDomain(dto).people

        people.map { it.name } shouldContainExactly listOf("Amy Adams", "Denis Villeneuve")
        people.first().kind shouldBe PersonKind.ACTOR
        people.first().role shouldBe "Louise Banks"
        people.first().primaryImageUrl!! shouldContain actorId.toString()
        people.last().kind shouldBe PersonKind.DIRECTOR
        people.last().role.shouldBeNull()
        people.last().primaryImageUrl.shouldBeNull()
    }

    @Test
    fun `folds credit kinds outside v1's scope into OTHER`() {
        fun kindOf(kind: SdkPersonKind) =
            mapper
                .toDomain(
                    BaseItemDto(
                        id = movieId,
                        type = BaseItemKind.MOVIE,
                        people = listOf(BaseItemPerson(id = movieId, name = "X", type = kind)),
                    ),
                ).people
                .single()
                .kind

        kindOf(SdkPersonKind.ACTOR) shouldBe PersonKind.ACTOR
        kindOf(SdkPersonKind.DIRECTOR) shouldBe PersonKind.DIRECTOR
        kindOf(SdkPersonKind.WRITER) shouldBe PersonKind.WRITER
        kindOf(SdkPersonKind.PRODUCER) shouldBe PersonKind.PRODUCER
        kindOf(SdkPersonKind.GUEST_STAR) shouldBe PersonKind.GUEST_STAR
        kindOf(SdkPersonKind.COMPOSER) shouldBe PersonKind.OTHER
    }

    @Test
    fun `treats a blank role as no role at all`() {
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                people =
                    listOf(BaseItemPerson(id = movieId, name = "X", role = "  ", type = SdkPersonKind.ACTOR)),
            )

        mapper
            .toDomain(dto)
            .people
            .single()
            .role
            .shouldBeNull()
    }

    // ---- M6: timezone regression --------------------------------------------------------------

    /**
     * Regression test for the M4 bug in STATUS.md's "Known issues": SDK date fields are *local*
     * wall-clock time (its serializer applies `ZoneId.systemDefault()`), so reading one as UTC
     * shifted every timestamp by the device's offset — two hours on the test device.
     */
    @Test
    fun `reads an SDK date field as local wall-clock time, not as UTC`() {
        val dto =
            BaseItemDto(
                id = movieId,
                type = BaseItemKind.MOVIE,
                premiereDate = LocalDateTime.of(2016, 11, 11, 14, 30),
            )

        // 14:30 in Europe/Paris on 11 Nov is 13:30Z — reading it as UTC would have produced 14:30Z.
        mapper.toDomain(dto).premiereDate shouldBe Instant.parse("2016-11-11T13:30:00Z")
    }

    private fun library(
        id: UUID,
        name: String,
        collectionType: CollectionType?,
    ) = BaseItemDto(
        id = id,
        type = BaseItemKind.COLLECTION_FOLDER,
        name = name,
        collectionType = collectionType,
    )

    private companion object {
        /** A fixed non-UTC zone with a non-zero offset all year round. */
        const val TEST_ZONE = "Europe/Paris"
    }
}
