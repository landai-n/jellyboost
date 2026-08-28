package dev.jellyboost.data.downloads.impl

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadApi
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.album
import dev.jellyboost.data.downloads.DownloadFixtures.artist
import dev.jellyboost.data.downloads.DownloadFixtures.audioStream
import dev.jellyboost.data.downloads.DownloadFixtures.episode
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.playlist
import dev.jellyboost.data.downloads.DownloadFixtures.season
import dev.jellyboost.data.downloads.DownloadFixtures.series
import dev.jellyboost.data.downloads.DownloadFixtures.track
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.engine.SiblingSeeder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The rule the tests exist for is step 3: the item **and its parents** are cached with
 * `source = DOWNLOAD`. Get that wrong and the download works perfectly while the offline library shows
 * an episode that cannot reach its own series page.
 *
 * The second half pins **container expansion**: a season or a series is a folder, and enqueueing one
 * has to become one download per episode rather than a row for the folder itself — the row that
 * produced *"The server couldn't send this download (error 400)"*.
 */
@Suppress("LargeClass") // One class per enqueue rule would separate each rule from the fixtures it shares.
class DownloadEnqueuerTest {
    private val api = mockk<DownloadApi>()
    private val itemDao = mockk<ItemDao>(relaxUnitFun = true)
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val deleter = mockk<DownloadDeleter>()
    private val mapper = mockk<ItemEntityMapper>()
    private val downloadQuality = MutableStateFlow(DownloadQuality.ORIGINAL)
    private val appPreferences =
        mockk<AppPreferences> {
            every { this@mockk.downloadQuality } returns this@DownloadEnqueuerTest.downloadQuality
        }
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private val upserted = slot<List<ItemEntity>>()
    private val rows = mutableListOf<DownloadEntity>()

    private val row: DownloadEntity get() = rows.single()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { downloadDao.upsert(capture(rows)) } just Runs
        coEvery { downloadDao.get(any()) } returns null
        // The season path reads its whole batch in one statement; routing it through the per-id stub
        // keeps every test below expressing its rows one at a time.
        coEvery { downloadDao.getAll(any()) } coAnswers {
            firstArg<List<UUID>>().mapNotNull { downloadDao.get(it) }
        }
        coEvery { downloadDao.maxQueuePosition() } returns null
        // No finished siblings and no cached runtimes by default: seeding is opt-in per test.
        coEvery { downloadDao.completedSiblings(any(), any(), any()) } returns emptyList()
        coEvery { itemDao.getItems(any()) } returns emptyList()
        coEvery { deleter.delete(any()) } returns 0L
        coEvery { downloadDao.demoteRunnable(any(), any(), any()) } returns false
        // `toEntity` is overloaded (items and library views), so the argument types are explicit.
        every { mapper.toEntity(any<BaseItemDto>(), any<ItemSource>(), any<Instant>()) } answers {
            entity(firstArg(), secondArg())
        }
    }

    // ---- the cache write ------------------------------------------------------------------------

    @Test
    fun `the item is cached as a download, never as browse cache`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(1))) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            // A BROWSE_CACHE row is evictable, and evicting it would orphan the files on disk.
            upserted.captured.map { it.source }.distinct() shouldContainExactlyInAnyOrder listOf(ItemSource.DOWNLOAD)
        }

    @Test
    fun `an episode caches its series and season alongside itself`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
                AppResult.Success(listOf(movie(id = uuid(10)), movie(id = uuid(11))))

            enqueuer().enqueue(uuid(2), USER)

            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(2), uuid(10), uuid(11))
        }

    @Test
    fun `a movie fetches no parents at all`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(1))) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            coVerify(exactly = 1) { api.getFullItems(any()) }
        }

    @Test
    fun `a failing parent fetch still enqueues the download`() =
        runTest {
            // Losing the series page offline is a degradation; losing the download is a failure.
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
                AppResult.Failure(AppError.Network())

            enqueuer().enqueue(uuid(2), USER).shouldBeInstanceOf<AppResult.Success<List<DownloadEntity>>>()
        }

    // ---- the download row -----------------------------------------------------------------------

    @Test
    fun `a new download starts QUEUED at the end of the queue`() =
        runTest {
            coEvery { downloadDao.maxQueuePosition() } returns 4
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.status shouldBe DownloadStatus.QUEUED
            row.queuePosition shouldBe 5
        }

    @Test
    fun `the row carries the expected size so the queue can show a percentage immediately`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.bytesTotal shouldBe 2_100_000_000L
            row.mediaSourceId shouldBe "source-1"
        }

    @Test
    fun `the row carries the directory the files will land in`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns AppResult.Success(emptyList())

            enqueuer().enqueue(uuid(2), USER)

            // Denormalised on purpose: the delete cascade needs it after the item row is gone.
            row.directoryName shouldBe "Westworld - S01E02 - Chestnut"
            row.seriesName shouldBe "Westworld"
        }

    // ---- the grouping columns -------------------------------------------------------------------

    @Test
    fun `an episode records its show, its kind and its show's id`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns AppResult.Success(emptyList())

            enqueuer().enqueue(uuid(2), USER)

            row.itemType shouldBe ItemType.EPISODE
            row.seriesName shouldBe "Westworld"
            row.albumName.shouldBeNull()
            row.groupId shouldBe uuid(10)
        }

    @Test
    fun `a track records its album and leaves the series column empty`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns AppResult.Success(listOf(track()))

            enqueuer().enqueue(uuid(40), USER)

            // An album in the series column is a downloaded album and a downloaded show that no reader
            // downstream — the sections, the size seeder — can tell apart.
            row.itemType shouldBe ItemType.AUDIO
            row.seriesName.shouldBeNull()
            row.albumName shouldBe "Rumours"
            row.artistName shouldBe "Fleetwood Mac"
            row.groupId shouldBe uuid(40)
        }

    @Test
    fun `a track with no album artist is credited to the artists it does name`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns
                AppResult.Success(
                    listOf(
                        track(
                            albumArtist = null,
                            albumArtistId = null,
                            artists = listOf("Fleetwood Mac", "Various Artists"),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(40), USER)

            row.artistName shouldBe "Fleetwood Mac, Various Artists"
        }

    @Test
    fun `a blank album artist is recorded as no artist rather than as an empty credit`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns
                AppResult.Success(listOf(track(albumArtist = "  ", albumArtistId = null)))

            enqueuer().enqueue(uuid(40), USER)

            row.artistName.shouldBeNull()
        }

    @Test
    fun `a film records no heading at all`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.itemType shouldBe ItemType.MOVIE
            row.seriesName.shouldBeNull()
            row.albumName.shouldBeNull()
            row.artistName.shouldBeNull()
            row.groupId.shouldBeNull()
        }

    @Test
    fun `a blank album is recorded as no album rather than as a heading of its own`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns
                AppResult.Success(listOf(track(album = "  ")))

            enqueuer().enqueue(uuid(40), USER)

            row.albumName.shouldBeNull()
        }

    @Test
    fun `re-enqueueing an item keeps its place in the queue and its bytes`() =
        runTest {
            val existing =
                DownloadFixtures.download(
                    status = DownloadStatus.ERROR,
                    queuePosition = 2,
                    bytesDownloaded = 900_000L,
                )
            coEvery { downloadDao.get(uuid(1)) } returns existing
            coEvery { downloadDao.maxQueuePosition() } returns 9
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            // Retrying a failure must not send the item to the back of the queue, nor throw away the
            // bytes already on disk that the Range resume will pick up from.
            row.queuePosition shouldBe 2
            row.bytesDownloaded shouldBe 900_000L
            row.status shouldBe DownloadStatus.QUEUED
            row.errorMessage shouldBe null
        }

    @Test
    fun `the created timestamp survives a re-enqueue`() =
        runTest {
            val earlier = Instant.parse("2026-07-01T08:00:00Z")
            // `ERROR`, because that is a state a re-enqueue actually writes over: a row still `QUEUED` is
            // one the second tap must leave alone.
            coEvery { downloadDao.get(uuid(1)) } returns
                DownloadFixtures.download(status = DownloadStatus.ERROR).copy(createdAt = earlier)
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.createdAt shouldBe earlier
            row.updatedAt shouldBe NOW
        }

    @Test
    fun `a second tap on a single item already downloaded leaves its row exactly as it is`() =
        runTest {
            // The container path filters these out, and the single path has to as well: writing over
            // whatever it finds would let a badge one tick stale — or a plain double tap — restamp a
            // finished row's quality, `bytesTotal` and `sizeIsExact` from the *current* preference.
            coEvery { downloadDao.get(uuid(1)) } returns
                DownloadFixtures.download(status = DownloadStatus.DOWNLOADED, quality = DownloadQuality.ORIGINAL)
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))
            downloadQuality.value = DownloadQuality.LOW

            enqueuer().enqueue(uuid(1), USER) shouldBe AppResult.Success(emptyList())

            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    @Test
    fun `a cancelled row is re-enqueued, because that is what re-downloading after a cancel is`() =
        runTest {
            // The state a cancel leaves behind: the cascade has not reached the row yet, the UI already
            // offers Download, and the tap has to write — the fresh QUEUED row is what makes the cascade
            // skip it.
            coEvery { downloadDao.get(uuid(1)) } returns
                DownloadFixtures.download(status = DownloadStatus.CANCELLED)
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.status shouldBe DownloadStatus.QUEUED
        }

    @Test
    fun `every row of one enqueue gets its own queue position`() =
        runTest {
            // The `maxQueuePosition()` read and the row writes are one transaction; the counter it seeds
            // still has to advance per row within it.
            givenSeason(episodeIds = listOf(uuid(2), uuid(3), uuid(4)))
            coEvery { downloadDao.maxQueuePosition() } returns 7
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3), uuid(4))) } returns
                AppResult.Success(listOf(episode(id = uuid(2)), episode(id = uuid(3)), episode(id = uuid(4))))

            enqueuer().enqueue(uuid(11), USER)

            rows.map { it.queuePosition } shouldContainExactly listOf(8, 9, 10)
        }

    // ---- failures -------------------------------------------------------------------------------

    @Test
    fun `a failed re-fetch writes nothing`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Failure(AppError.Network())

            enqueuer().enqueue(uuid(1), USER).shouldBeInstanceOf<AppResult.Failure>()

            coVerify(exactly = 0) { itemDao.upsert(any()) }
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    @Test
    fun `an item the server no longer knows is a NotFound, not a crash`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(emptyList())

            val result = enqueuer().enqueue(uuid(1), USER)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
        }

    @Test
    fun `a failing cache write fails the enqueue rather than queueing an invisible download`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))
            coEvery { itemDao.upsert(any()) } throws SQLiteException("disk full")

            val result = enqueuer().enqueue(uuid(1), USER)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Storage>()
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    /**
     * The enqueue runs in the caller's scope — a ViewModel's, which dies with the screen. Turning that
     * cancellation into `AppError.Storage` would put a "could not download" message on a screen the user
     * has already left, and would swallow the cancellation the parent job is owed.
     */
    @Test
    fun `a cancelled enqueue propagates instead of being reported as a storage failure`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))
            coEvery { itemDao.upsert(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { enqueuer().enqueue(uuid(1), USER) }
        }

    // ---- download quality ------------------------------------------------------------------------

    @Test
    fun `the preference in force when the user taps Download is stamped on the row`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            // Stored, not re-read later: the queue plans every run from this column, so a user who
            // changes the setting mid-transfer cannot make the pipeline append incompatible bytes.
            row.quality shouldBe DownloadQuality.MEDIUM
        }

    // ---- the baked audio track (schema v8) -------------------------------------------------------

    @Test
    fun `a transcoded row records the audio track the download asked the server to bake in`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            streams = listOf(audioStream(index = 1), audioStream(index = 2, language = "fra")),
                            defaultAudioStreamIndex = 2,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // The file will hold exactly one audio track; the cached blob still describes three, so this
            // column is the only thing that knows which of them survived.
            row.bakedAudioStreamIndex shouldBe 2
        }

    @Test
    fun `a transcoded row falls back to the first audio stream when nothing is default`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(streams = listOf(audioStream(index = 1), audioStream(index = 2)))))

            enqueuer().enqueue(uuid(1), USER)

            row.bakedAudioStreamIndex shouldBe 1
        }

    @Test
    fun `an original row records no baked track, because it keeps them all`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(movie(streams = listOf(audioStream(index = 1)), defaultAudioStreamIndex = 1)),
                )

            enqueuer().enqueue(uuid(1), USER)

            row.bakedAudioStreamIndex.shouldBeNull()
        }

    @Test
    fun `a transcoded item with no audio streams records no baked track either`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            // The URL names no index either; a pin for a track that does not exist would be a lie the
            // offline picker would then label from.
            row.bakedAudioStreamIndex.shouldBeNull()
        }

    @Test
    fun `each episode of a season records its own baked track`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            givenSeason(episodeIds = listOf(uuid(2), uuid(3)))
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3))) } returns
                AppResult.Success(
                    listOf(
                        episode(
                            id = uuid(2),
                            streams = listOf(audioStream(index = 1), audioStream(index = 2)),
                            defaultAudioStreamIndex = 2,
                        ),
                        episode(
                            id = uuid(3),
                            episodeNumber = 3,
                            streams = listOf(audioStream(index = 1), audioStream(index = 4)),
                            defaultAudioStreamIndex = 4,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(11), USER)

            // One quality for the whole season, but the stream numbering is each episode's own — a
            // season-wide index would name a different language halfway through the show.
            rows.map { it.bakedAudioStreamIndex } shouldContainExactly listOf(2, 4)
        }

    @Test
    fun `an original download keeps the exact size the server reported`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(sizeBytes = 2_100_000_000L, runTimeTicks = HOUR_TICKS)))

            enqueuer().enqueue(uuid(1), USER)

            row.bytesTotal shouldBe 2_100_000_000L
        }

    @Test
    fun `a transcoded download is sized from its runtime and bitrate instead`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            // What that source bitrate weighs over an hour: twice the estimate
                            // below, so the transcode is worth making and the row keeps it.
                            sizeBytes = 3_600L * DownloadQuality.MEDIUM.totalBitRate!! * 2 / 8,
                            // Above the MEDIUM cap, so the cap — not the source — bounds the estimate.
                            sourceBitRate = DownloadQuality.MEDIUM.totalBitRate!! * 2,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // The server will not send a Content-Length for a file it has not encoded yet, so an hour at
            // 8 Mbps + 192 kbps of audio is the only number the queue tab can show.
            val expected = 3_600L * (DownloadQuality.MEDIUM.videoBitRate!! + DownloadQuality.AUDIO_BITRATE) / 8
            row.bytesTotal shouldBe expected
        }

    @Test
    fun `a transcoded download of a source under the cap is sized from the source bitrate`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            // Below the LOW cap — an HEVC source, say — so the transcode cannot need more bits per
            // second than the source already uses.
            val sourceBitRate = 1_500_000
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            // No size reported for this source, so the fall-back-to-original rule has
                            // nothing to compare the estimate against and leaves the choice alone.
                            sizeBytes = null,
                            sourceBitRate = sourceBitRate,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            val expected = 3_600L * sourceBitRate / 8
            row.bytesTotal shouldBe expected
        }

    @Test
    fun `a transcoded download with no source bitrate falls back to the quality cap`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sizeBytes = 2_100_000_000L,
                            sourceBitRate = null,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            val expected = 3_600L * (DownloadQuality.LOW.videoBitRate!! + DownloadQuality.AUDIO_BITRATE) / 8
            row.bytesTotal shouldBe expected
        }

    @Test
    fun `a transcoded download of an item with no runtime falls back to an unknown size`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(sizeBytes = 2_100_000_000L, runTimeTicks = null)))

            enqueuer().enqueue(uuid(1), USER)

            // Zero is the pipeline's "unknown", which renders as an indeterminate bar. Reporting the
            // *source* size here would promise a file the user is not going to get.
            row.bytesTotal shouldBe 0L
        }

    // ---- containers expand into episodes ---------------------------------------------------------

    @Test
    fun `a season becomes one download per episode, in the order the server lists them`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2), uuid(3)))
            // Deliberately answered out of order: `getItems(ids = …)` sorts to its own taste, and the
            // queue's order is the one that was asked for.
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(3), episodeNumber = 3), episode(id = uuid(2))))

            val result = enqueuer().enqueue(uuid(11), USER)

            // The season itself is never a download row: `/Items/{id}/Download` answers 400 for a folder,
            // which is the bug this whole expansion exists to fix.
            rows.map { it.itemId } shouldContainExactly listOf(uuid(2), uuid(3))
            rows.map { it.queuePosition } shouldContainExactly listOf(1, 2)
            result.shouldBeInstanceOf<AppResult.Success<List<DownloadEntity>>>().value.size shouldBe 2
        }

    @Test
    fun `a series is expanded across every one of its seasons at once`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(10))) } returns AppResult.Success(listOf(series()))
            coEvery { api.getEpisodeIds(uuid(10), null) } returns AppResult.Success(listOf(uuid(2), uuid(3)))
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(2)), episode(id = uuid(3), episodeNumber = 3)))
            coEvery { api.getFullItems(listOf(uuid(11))) } returns AppResult.Success(listOf(season()))

            enqueuer().enqueue(uuid(10), USER)

            coVerify(exactly = 1) { api.getEpisodeIds(uuid(10), null) }
            rows.map { it.itemId } shouldContainExactly listOf(uuid(2), uuid(3))
        }

    @Test
    fun `episodes already spoken for are left exactly as they are`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2), uuid(3)))
            coEvery { downloadDao.get(uuid(2)) } returns
                DownloadFixtures.download(itemId = uuid(2), status = DownloadStatus.DOWNLOADED)
            coEvery { api.getFullItems(listOf(uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(3), episodeNumber = 3)))

            enqueuer().enqueue(uuid(11), USER)

            // Re-tapping Download on a half-downloaded season must not restart what is already there —
            // and must not re-fetch it either.
            rows.map { it.itemId } shouldContainExactly listOf(uuid(3))
            coVerify(exactly = 0) { api.getFullItems(listOf(uuid(2), uuid(3))) }
        }

    @Test
    fun `a failed episode is the one thing a second tap does re-enqueue`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { downloadDao.get(uuid(2)) } returns
                DownloadFixtures.download(
                    itemId = uuid(2),
                    status = DownloadStatus.ERROR,
                    queuePosition = 4,
                    bytesDownloaded = 900_000L,
                )
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            row.status shouldBe DownloadStatus.QUEUED
            row.queuePosition shouldBe 4
            row.bytesDownloaded shouldBe 900_000L
        }

    @Test
    fun `the season's own unusable download row is cleaned up as the episodes are queued`() =
        runTest {
            // The rows the user is stuck with: a season enqueued as if it were a file, permanently ERROR
            // because no retry of `/Items/{seasonId}/Download` can ever succeed.
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { downloadDao.get(uuid(11)) } returns
                DownloadFixtures.download(itemId = uuid(11), status = DownloadStatus.ERROR)
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            coVerifyOrder {
                // The claim first: the cascade only removes rows out of the queue's reach, and a doomed
                // container row never leaves QUEUED/ERROR on its own.
                downloadDao.demoteRunnable(listOf(uuid(11)), DownloadStatus.CANCELLED, NOW)
                deleter.delete(uuid(11))
            }
            coVerify(exactly = 1) { deleter.delete(uuid(11)) }
            rows.map { it.itemId } shouldContainExactly listOf(uuid(2))
        }

    @Test
    fun `a container with no row of its own has nothing to clean up`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            coVerify(exactly = 0) { deleter.delete(any()) }
        }

    @Test
    fun `every episode of a season is stamped with the one quality in force at the tap`() =
        runTest {
            downloadQuality.value = DownloadQuality.MEDIUM
            givenSeason(episodeIds = listOf(uuid(2), uuid(3)))
            coEvery { api.getFullItems(listOf(uuid(2), uuid(3))) } returns
                AppResult.Success(listOf(episode(id = uuid(2)), episode(id = uuid(3), episodeNumber = 3)))

            enqueuer().enqueue(uuid(11), USER)

            // One tap, one quality: a preference changed while the season drains cannot make half the
            // episodes a different file.
            rows.map { it.quality }.distinct() shouldContainExactly listOf(DownloadQuality.MEDIUM)
        }

    @Test
    fun `expanding a season caches the season, its series and every episode`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))

            enqueuer().enqueue(uuid(11), USER)

            // Without the season and series rows the downloaded episodes cannot be navigated to offline —
            // the same rule a single episode download follows.
            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(11), uuid(2), uuid(10))
        }

    @Test
    fun `a season whose every episode is already downloaded queues nothing and reports success`() =
        runTest {
            givenSeason(episodeIds = listOf(uuid(2)))
            coEvery { downloadDao.get(uuid(2)) } returns
                DownloadFixtures.download(itemId = uuid(2), status = DownloadStatus.DOWNLOADED)

            val result = enqueuer().enqueue(uuid(11), USER)

            result.shouldBeInstanceOf<AppResult.Success<List<DownloadEntity>>>().value.shouldBeEmpty()
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    @Test
    fun `a season the server lists no episodes for fails instead of queueing the season`() =
        runTest {
            givenSeason(episodeIds = emptyList())

            val result = enqueuer().enqueue(uuid(11), USER)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    @Test
    fun `a folder this pipeline cannot expand is refused, never queued`() =
        runTest {
            // A box set has no episodes endpoint and no file of its own; queueing it would recreate
            // exactly the 400 this fix removes.
            val boxSet =
                BaseItemDto(id = uuid(20), type = BaseItemKind.BOX_SET, name = "Trilogy", isFolder = true)
            coEvery { api.getFullItems(listOf(uuid(20))) } returns AppResult.Success(listOf(boxSet))

            enqueuer().enqueue(uuid(20), USER).shouldBeInstanceOf<AppResult.Failure>()

            coVerify(exactly = 0) { downloadDao.upsert(any()) }
            coVerify(exactly = 0) { api.getEpisodeIds(any(), any()) }
        }

    @Test
    fun `a failing episode listing writes nothing`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(11))) } returns AppResult.Success(listOf(season()))
            coEvery { api.getEpisodeIds(uuid(10), uuid(11)) } returns AppResult.Failure(AppError.Network())

            enqueuer().enqueue(uuid(11), USER).shouldBeInstanceOf<AppResult.Failure>()

            coVerify(exactly = 0) { itemDao.upsert(any()) }
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    // ---- music containers expand too -------------------------------------------------------------

    @Test
    fun `an album becomes one download per track, in the order the server lists them`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30), uuid(31)))
            // Answered out of order on purpose: `getItems(ids = …)` sorts to its own taste, and the
            // queue's order is the disc/track order that was asked for.
            coEvery { api.getFullItems(listOf(uuid(30), uuid(31))) } returns
                AppResult.Success(listOf(track(id = uuid(31), trackNumber = 5), track(id = uuid(30))))

            val result = enqueuer().enqueue(uuid(40), USER)

            rows.map { it.itemId } shouldContainExactly listOf(uuid(30), uuid(31))
            rows.map { it.queuePosition } shouldContainExactly listOf(1, 2)
            result.shouldBeInstanceOf<AppResult.Success<List<DownloadEntity>>>().value.size shouldBe 2
        }

    @Test
    fun `downloading an album caches the album and the artist so the offline walk works`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns AppResult.Success(listOf(track()))

            enqueuer().enqueue(uuid(40), USER)

            // artist → album → tracks is the offline walk, and every hop reads a row with
            // `source = DOWNLOAD`. A missing artist row is an artist page with nothing on it.
            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(40), uuid(30), uuid(50))
            upserted.captured.map { it.source }.distinct() shouldContainExactly listOf(ItemSource.DOWNLOAD)
        }

    @Test
    fun `an artist is expanded through one album-ordered request`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(50))) } returns AppResult.Success(listOf(artist()))
            coEvery { api.getArtistTrackIds(uuid(50)) } returns AppResult.Success(listOf(uuid(30), uuid(32)))
            coEvery { api.getFullItems(listOf(uuid(30), uuid(32))) } returns
                AppResult.Success(
                    listOf(
                        track(id = uuid(30)),
                        track(id = uuid(32), albumId = uuid(41), album = "Tusk", trackNumber = 1),
                    ),
                )
            coEvery { api.getFullItems(listOf(uuid(40), uuid(41))) } returns
                AppResult.Success(listOf(album(), album(id = uuid(41), name = "Tusk")))

            enqueuer().enqueue(uuid(50), USER)

            coVerify(exactly = 1) { api.getArtistTrackIds(uuid(50)) }
            rows.map { it.itemId } shouldContainExactly listOf(uuid(30), uuid(32))
            // Both albums are cached — the artist page lists them, and each one's own page lists the
            // tracks underneath it.
            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder
                listOf(uuid(50), uuid(30), uuid(32), uuid(40), uuid(41))
        }

    @Test
    fun `a playlist queues its members' albums and artists, but not the playlist itself`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(60))) } returns AppResult.Success(listOf(playlist()))
            coEvery { api.getPlaylistTrackIds(uuid(60)) } returns AppResult.Success(listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns AppResult.Success(listOf(track()))
            coEvery { api.getFullItems(listOf(uuid(40), uuid(50))) } returns
                AppResult.Success(listOf(album(), artist()))

            enqueuer().enqueue(uuid(60), USER)

            rows.map { it.itemId } shouldContainExactly listOf(uuid(30))
            // The playlist row is deliberately absent: offline it could only ever open onto an empty
            // track list, because Room has no playlist-membership relation. The tracks are reachable
            // through their album and artist instead.
            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(30), uuid(40), uuid(50))
        }

    @Test
    fun `a single track caches its album and its artist`() =
        runTest {
            coEvery { api.getFullItems(listOf(uuid(30))) } returns AppResult.Success(listOf(track()))
            coEvery { api.getFullItems(listOf(uuid(40), uuid(50))) } returns
                AppResult.Success(listOf(album(), artist()))

            enqueuer().enqueue(uuid(30), USER)

            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(30), uuid(40), uuid(50))
        }

    @Test
    fun `tracks already on the device are left exactly as they are`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30), uuid(31)))
            coEvery { downloadDao.get(uuid(30)) } returns
                DownloadFixtures.download(itemId = uuid(30), status = DownloadStatus.DOWNLOADED)
            coEvery { api.getFullItems(listOf(uuid(31))) } returns
                AppResult.Success(listOf(track(id = uuid(31), trackNumber = 5)))

            enqueuer().enqueue(uuid(40), USER)

            rows.map { it.itemId } shouldContainExactly listOf(uuid(31))
        }

    @Test
    fun `a track is always downloaded as the original, whatever the quality preference says`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns AppResult.Success(listOf(track()))

            enqueuer().enqueue(uuid(40), USER)

            // Music is originals-only and the *row* says so: every downstream rule keys off this column
            // — the transcode URL, the size projector, the no-pause rule, the *Transcoded* marker — so a
            // row written ORIGINAL is one none of that machinery can reach.
            row.quality shouldBe DownloadQuality.ORIGINAL
            row.bakedAudioStreamIndex.shouldBeNull()
            row.bytesTotal shouldBe 32_000_000L
            row.sizeIsExact shouldBe true
            row.projectedBytes.shouldBeNull()
        }

    @Test
    fun `a track files itself under its album on the Downloads screen`() =
        runTest {
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns AppResult.Success(listOf(track()))

            enqueuer().enqueue(uuid(40), USER)

            // The column the Downloaded tab groups by (`DownloadItem.groupTitle`): a track's heading is
            // its album, the way an episode's is its show.
            row.albumName shouldBe "Rumours"
            // And the directory is unique per track — two albums' *Intro* must not share one.
            row.directoryName shouldBe "Fleetwood Mac - Rumours - 04 - Go Your Own Way"
        }

    @Test
    fun `a track never reaches the size seeder at all`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            givenAlbum(trackIds = listOf(uuid(30)))
            coEvery { api.getFullItems(listOf(uuid(30))) } returns AppResult.Success(listOf(track()))

            enqueuer().enqueue(uuid(40), USER)

            // `planQuality` stamps music `ORIGINAL` before any transcode branch, and the seeder only
            // answers for a transcoded row — which is why moving albums out of `seriesName` costs the
            // sibling-size lookups nothing.
            coVerify(exactly = 0) { downloadDao.completedSiblings(any(), any(), any()) }
        }

    @Test
    fun `an album the server lists no tracks for fails instead of queueing the album`() =
        runTest {
            givenAlbum(trackIds = emptyList())

            val result = enqueuer().enqueue(uuid(40), USER)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { downloadDao.upsert(any()) }
        }

    // ---- helpers --------------------------------------------------------------------------------

    /** An album (`uuid(40)`) by an artist (`uuid(50)`) the server lists [trackIds] under. */
    private fun givenAlbum(trackIds: List<java.util.UUID>) {
        coEvery { api.getFullItems(listOf(uuid(40))) } returns AppResult.Success(listOf(album()))
        coEvery { api.getAlbumTrackIds(uuid(40)) } returns AppResult.Success(trackIds)
        // The tracks' parents: the album is already cached, so only the artist is fetched.
        coEvery { api.getFullItems(listOf(uuid(50))) } returns AppResult.Success(listOf(artist()))
    }

    /** A season (`uuid(11)`) of a series (`uuid(10)`) the server lists [episodeIds] under. */
    private fun givenSeason(episodeIds: List<java.util.UUID>) {
        coEvery { api.getFullItems(listOf(uuid(11))) } returns AppResult.Success(listOf(season()))
        coEvery { api.getEpisodeIds(uuid(10), uuid(11)) } returns AppResult.Success(episodeIds)
        // The episodes' parents: the season is already cached, so only the series is fetched.
        coEvery { api.getFullItems(listOf(uuid(10))) } returns AppResult.Success(listOf(series()))
    }

    private fun enqueuer() =
        DownloadEnqueuer(
            api = api,
            itemDao = itemDao,
            downloadDao = downloadDao,
            deleter = deleter,
            mapper = mapper,
            appPreferences = appPreferences,
            seeder = SiblingSeeder(downloadDao = downloadDao, itemDao = itemDao, clock = clock),
            transactionRunner = DownloadFixtures.directTransactionRunner,
            clock = clock,
        )

    private fun entity(
        dto: BaseItemDto,
        source: ItemSource,
    ) = ItemEntity(
        id = dto.id,
        name = dto.name.orEmpty(),
        sortName = dto.name.orEmpty(),
        type = ItemType.MOVIE,
        source = source,
        cachedAt = NOW,
        dto = "{}",
    )

    private companion object {
        val USER = uuid(99)

        /** One hour in `runTimeTicks` (100 ns each). */
        const val HOUR_TICKS = 36_000_000_000L
    }
}
