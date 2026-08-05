package dev.jellyboost.data.cache

import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.data.mapper.FakeImageUrlFactory
import dev.jellyboost.data.mapper.ItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Fixtures shared by the M6 cache and offline-repository tests.
 *
 * Rows are built by running real DTOs through the real [ItemEntityMapper] rather than hand-writing
 * `ItemEntity` literals: the blob is the thing the offline path reads back, so a fixture that
 * faked it would test nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * A real [BrowseCacheMaintenance] for the tests that only need `BrowseCacheWriter` to have one.
     *
     * Real rather than a mock because what the writer does with it is count: `onWriteThrough` is an
     * atomic increment for all but every `WRITES_BETWEEN_SWEEPS`th call, so a handful of writes
     * touches [itemDao] not at all and a test asserting on the writer's own statements sees exactly
     * what it did before the counter existed. `BrowseCacheMaintenanceTest` owns the sweep itself.
     */
    fun maintenance(
        scope: CoroutineScope,
        itemDao: ItemDao,
        clock: Clock,
    ) = BrowseCacheMaintenance(
        itemDao = itemDao,
        clock = clock,
        scope = scope,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

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

    // ---- M13 Phase 2 — music fixtures ------------------------------------------------------

    fun audioDto(
        id: UUID,
        name: String,
        albumId: UUID? = null,
        albumArtistId: UUID? = null,
        discNumber: Int? = null,
        trackNumber: Int? = null,
        parentId: UUID? = albumId,
        /** Cached on the DTO's own `userData`, the way a server response actually carries it. */
        playCount: Int = 0,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.AUDIO,
            name = name,
            albumId = albumId,
            albumArtists = albumArtistId?.let { listOf(NameGuidPair(id = it, name = "Artist")) },
            parentIndexNumber = discNumber,
            indexNumber = trackNumber,
            parentId = parentId,
            runTimeTicks = 200_000_000L,
            // UserItemDataDto's constructor has no defaults of its own (unlike BaseItemDto's), so
            // every field is spelled out here — the same shape ItemMapperTest's own fixture uses.
            userData =
                if (playCount > 0) {
                    UserItemDataDto(
                        rating = null,
                        playedPercentage = null,
                        unplayedItemCount = null,
                        playbackPositionTicks = 0L,
                        playCount = playCount,
                        isFavorite = false,
                        likes = null,
                        lastPlayedDate = null,
                        played = false,
                        key = id.toString(),
                        itemId = id,
                    )
                } else {
                    null
                },
        )

    fun albumDto(
        id: UUID,
        name: String,
        albumArtistId: UUID? = null,
        productionYear: Int? = null,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.MUSIC_ALBUM,
            name = name,
            albumArtists = albumArtistId?.let { listOf(NameGuidPair(id = it, name = "Artist")) },
            productionYear = productionYear,
        )

    fun artistDto(
        id: UUID,
        name: String,
    ): BaseItemDto = BaseItemDto(id = id, type = BaseItemKind.MUSIC_ARTIST, name = name)

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

/**
 * Stands in for Room's `withTransaction`: it simply runs the block, and records enough for a
 * test to assert *that* the work happened inside one — which is the property the fix adds, and
 * the one no amount of mocked DAO calls can otherwise see.
 */
internal class RecordingTransactionRunner : TransactionRunner {
    /** How deep in nested transactions the calling code currently is; 0 means none. */
    var depth: Int = 0
        private set

    /** How many transactions were opened in total. */
    var opened: Int = 0
        private set

    /** How many ended by throwing — Room's rollback path. */
    var rolledBack: Int = 0
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        opened++
        depth++
        var committed = false
        try {
            val result = block()
            committed = true
            return result
        } finally {
            depth--
            if (!committed) rolledBack++
        }
    }
}
