package dev.jellyboost.data.cache

import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.data.mapper.FakeImageUrlFactory
import dev.jellyboost.data.mapper.ItemMapper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.time.Instant
import java.util.UUID

/**
 * Fixtures shared by the M6 cache and offline-repository tests.
 *
 * Rows are built by running real DTOs through the real [ItemEntityMapper] rather than hand-writing
 * `ItemEntity` literals: the blob is the thing the offline path reads back, so a fixture that
 * faked it would test nothing.
 */
@Suppress("LongParameterList")
internal object CacheFixtures {
    val NOW: Instant = Instant.parse("2026-07-28T10:00:00Z")
    val USER_ID: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")
    val MOVIES_LIBRARY: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    val SHOWS_LIBRARY: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    val mapper = ItemEntityMapper(ItemMapper(FakeImageUrlFactory()), FakeImageUrlFactory())

    /**
     * A [TransactionRunner] that just runs the block — the right stand-in for every test that is
     * *not* about atomicity itself (`BrowseCacheWriterTest` has a recording one for those). Room's
     * real transaction is a device concern; what a JVM test can assert is the decision it wraps.
     */
    val directTransactionRunner =
        object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }

    fun uuid(seed: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    fun movieDto(
        id: UUID,
        name: String,
        parentId: UUID? = MOVIES_LIBRARY,
        sortName: String? = null,
        productionYear: Int? = 2016,
        genres: List<String> = listOf("Science Fiction"),
        officialRating: String? = "PG-13",
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.MOVIE,
            name = name,
            sortName = sortName,
            parentId = parentId,
            productionYear = productionYear,
            genres = genres,
            officialRating = officialRating,
            runTimeTicks = 60_000_000_000L,
        )

    fun episodeDto(
        id: UUID,
        name: String,
        seriesId: UUID,
        seriesName: String,
        seasonId: UUID,
        seasonNumber: Int,
        episodeNumber: Int,
        seriesPrimaryImageTag: String? = null,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.EPISODE,
            name = name,
            seriesId = seriesId,
            seriesName = seriesName,
            seasonId = seasonId,
            parentId = seasonId,
            parentIndexNumber = seasonNumber,
            indexNumber = episodeNumber,
            runTimeTicks = 24_000_000_000L,
            seriesPrimaryImageTag = seriesPrimaryImageTag,
        )

    fun seriesDto(
        id: UUID,
        name: String,
        parentId: UUID = SHOWS_LIBRARY,
        primaryImageTag: String? = null,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.SERIES,
            name = name,
            parentId = parentId,
            imageTags = primaryImageTag?.let { mapOf(ImageType.PRIMARY to it) },
        )

    fun seasonDto(
        id: UUID,
        name: String,
        seriesId: UUID,
        seasonNumber: Int,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.SEASON,
            name = name,
            seriesId = seriesId,
            parentId = seriesId,
            indexNumber = seasonNumber,
        )

    fun entity(
        dto: BaseItemDto,
        source: ItemSource = ItemSource.DOWNLOAD,
        cachedAt: Instant = NOW,
    ): ItemEntity = mapper.toEntity(dto, source, cachedAt)

    fun userData(
        itemId: UUID,
        played: Boolean = false,
        positionTicks: Long = 0L,
        lastPlayedDate: Instant? = null,
        isFavorite: Boolean = false,
    ): UserDataEntity =
        UserDataEntity(
            itemId = itemId,
            userId = USER_ID,
            played = played,
            isFavorite = isFavorite,
            playbackPositionTicks = positionTicks,
            lastPlayedDate = lastPlayedDate,
            updatedAt = NOW,
        )
}
