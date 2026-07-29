package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.episode
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.season
import dev.jellyfinnative.data.downloads.DownloadFixtures.series
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import dev.jellyfinnative.data.downloads.DownloadFixtures.videoStream
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for the **size** `DownloadEnqueuer` stamps on a row, and how well it claims to know it.
 *
 * Split out of [DownloadEnqueuerTest] because it is a self-contained question with three answers,
 * each with its own failure mode: the server's exact figure for an original; an arithmetic figure
 * for a transcode the server will answer by *copying* the video track; and, for a real re-encode, a
 * deterministic ceiling optionally improved by what finished episodes of the same show actually
 * weighed (docs/notes/download-size-estimation.md, schema v6).
 */
class DownloadEnqueuerSizeTest {
    private val api = mockk<DownloadApi>()
    private val itemDao = mockk<ItemDao>(relaxUnitFun = true)
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val deleter = mockk<DownloadDeleter>()
    private val mapper = mockk<ItemEntityMapper>()
    private val downloadQuality = MutableStateFlow(DownloadQuality.ORIGINAL)
    private val appPreferences =
        mockk<AppPreferences> {
            every { this@mockk.downloadQuality } returns this@DownloadEnqueuerSizeTest.downloadQuality
        }
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private val upserted = slot<List<ItemEntity>>()
    private val rows = mutableListOf<DownloadEntity>()

    /** The single row each of these enqueues writes. */
    private val row: DownloadEntity get() = rows.single()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { downloadDao.upsert(capture(rows)) } just Runs
        coEvery { downloadDao.get(any()) } returns null
        coEvery { downloadDao.maxQueuePosition() } returns null
        // No finished siblings and no cached runtimes by default: seeding is opt-in per test.
        coEvery { downloadDao.completedSiblings(any(), any(), any()) } returns emptyList()
        coEvery { itemDao.getItems(any()) } returns emptyList()
        coEvery { deleter.delete(any()) } returns 0L
        every { mapper.toEntity(any<BaseItemDto>(), any<ItemSource>(), any<Instant>()) } answers {
            cachedItem(firstArg(), secondArg())
        }
    }

    // ---- exact vs ceiling (schema v6) ------------------------------------------------------------

    @Test
    fun `an original download's size is exact, because the server measured it`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns AppResult.Success(listOf(movie()))

            enqueuer().enqueue(uuid(1), USER)

            row.sizeIsExact shouldBe true
        }

    @Test
    fun `a re-encoded download's size is only ever a ceiling`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            runTimeTicks = HOUR_TICKS,
                            // HEVC: not the codec the transcode asks for, so it must be re-encoded.
                            streams = listOf(videoStream(codec = "hevc", bitRate = 2_000_000)),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            row.sizeIsExact shouldBe false
        }

    // ---- remux detection (the server will stream-copy the video track) ---------------------------

    @Test
    fun `a source the server can stream-copy is sized as video plus one AAC track, exactly`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            val videoBitRate = 6_000_000
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            // Well under HIGH's cap, so the source's own total bitrate is what the
                            // old estimate would have used — and it is the wrong number here.
                            sourceBitRate = 6_500_000,
                            runTimeTicks = HOUR_TICKS,
                            streams = listOf(videoStream(bitRate = videoBitRate, height = 1080)),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // `allowVideoStreamCopy=true` + h264 + height ≤ 1080 + bitrate ≤ cap: the server passes
            // the video through untouched and re-encodes only the audio.
            val expected = 3_600L * (videoBitRate + DownloadQuality.AUDIO_BITRATE) / 8
            row.bytesTotal shouldBe expected
            row.sizeIsExact shouldBe true
        }

    @Test
    fun `a source whose video codec is not h264 is not a stream copy`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = 6_500_000,
                            runTimeTicks = HOUR_TICKS,
                            streams = listOf(videoStream(codec = "hevc")),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // `SupportedVideoCodecs` is our `videoCodec=h264` and the server's test is exact string
            // equality, so an HEVC source is re-encoded however small it is.
            row.sizeIsExact shouldBe false
            row.bytesTotal shouldBe 3_600L * 6_500_000 / 8
        }

    @Test
    fun `a source taller than the quality's maxHeight is not a stream copy`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            runTimeTicks = HOUR_TICKS,
                            // 1080p asked for at LOW, which caps at 720p: it has to be scaled.
                            streams = listOf(videoStream(height = 1080, bitRate = 1_000_000)),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            row.sizeIsExact shouldBe false
        }

    @Test
    fun `a source above the quality's video bitrate is not a stream copy`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            runTimeTicks = HOUR_TICKS,
                            // 720p, h264 — but four times LOW's 3 Mbps ceiling.
                            streams = listOf(videoStream(height = 720, bitRate = 12_000_000)),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            row.sizeIsExact shouldBe false
        }

    @Test
    fun `a source with no per-stream video bitrate is not a stream copy`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = 5_000_000,
                            runTimeTicks = HOUR_TICKS,
                            streams = listOf(videoStream(bitRate = null)),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // Not merely caution: `CanStreamCopyVideo` fails a null stream bitrate outright (there
            // is a live-stream escape hatch, and a download has no live stream). Plenty of MKVs
            // report no per-stream bitrate and transcode however small they are.
            row.sizeIsExact shouldBe false
        }

    @Test
    fun `a source with no video stream at all is not a stream copy`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(sourceBitRate = 5_000_000, runTimeTicks = HOUR_TICKS)))

            enqueuer().enqueue(uuid(1), USER)

            row.sizeIsExact shouldBe false
        }

    @Test
    fun `an avi source is never claimed as a stream copy`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            runTimeTicks = HOUR_TICKS,
                            sourceContainer = "avi",
                            streams = listOf(videoStream(bitRate = 4_000_000)),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // `CanStreamCopyVideo` has a special case for an AVI input that can force a re-encode.
            row.sizeIsExact shouldBe false
        }

    @Test
    fun `an original download is never treated as a remux, whatever its streams say`() =
        runTest {
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sizeBytes = 2_100_000_000L,
                            runTimeTicks = HOUR_TICKS,
                            streams = listOf(videoStream()),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // ORIGINAL never asks for a transcode, so there is nothing to copy: its size is the
            // server's own file size, untouched by any of this.
            row.bytesTotal shouldBe 2_100_000_000L
            row.sizeIsExact shouldBe true
        }

    // ---- sibling seeding (schema v6) -------------------------------------------------------------

    @Test
    fun `an episode is seeded from the median of its finished siblings at the same quality`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            // Three finished episodes at 100, 200 and 900 MB per hour. The median — 200 MB/h — is
            // the answer; the mean would be dragged to 400 by the one outlier.
            givenSiblings(
                uuid(31) to 100_000_000L,
                uuid(32) to 200_000_000L,
                uuid(33) to 900_000_000L,
            )
            givenTranscodedEpisode()

            enqueuer().enqueue(uuid(2), USER)

            row.projectedBytes shouldBe 200_000_000L
        }

    @Test
    fun `an even number of siblings averages the two middle rates`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            givenSiblings(uuid(31) to 100_000_000L, uuid(32) to 300_000_000L)
            givenTranscodedEpisode()

            enqueuer().enqueue(uuid(2), USER)

            row.projectedBytes shouldBe 200_000_000L
        }

    @Test
    fun `the seed is scaled by this episode's own runtime, not the siblings'`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            // One sibling: an hour long, 200 MB. This episode is half an hour.
            givenSiblings(uuid(31) to 200_000_000L)
            givenTranscodedEpisode(runTimeTicks = HOUR_TICKS / 2)

            enqueuer().enqueue(uuid(2), USER)

            row.projectedBytes shouldBe 100_000_000L
        }

    @Test
    fun `the seed can never exceed the ceiling`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            // A sibling far larger than LOW's own cap allows — a stale row, a different source.
            givenSiblings(uuid(31) to 90_000_000_000L)
            givenTranscodedEpisode()

            enqueuer().enqueue(uuid(2), USER)

            // The ceiling is still a promise. The seed may only move the figure down.
            row.projectedBytes shouldBe row.bytesTotal
        }

    @Test
    fun `siblings downloaded at another quality are not evidence`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            givenTranscodedEpisode()
            // The DAO is asked for LOW; the ORIGINAL rows sitting next to them never come back.
            coEvery { downloadDao.completedSiblings("Westworld", DownloadQuality.LOW, any()) } returns emptyList()

            enqueuer().enqueue(uuid(2), USER)

            row.projectedBytes.shouldBeNull()
            coVerify { downloadDao.completedSiblings("Westworld", DownloadQuality.LOW, any()) }
        }

    @Test
    fun `the first episode of a series has nothing to be seeded from`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            givenTranscodedEpisode()

            enqueuer().enqueue(uuid(2), USER)

            row.projectedBytes.shouldBeNull()
        }

    @Test
    fun `a sibling whose runtime is not cached is skipped rather than guessed at`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            givenSiblings(uuid(31) to 200_000_000L)
            // The item row is gone (a wiped cache), so bytes-per-millisecond cannot be computed.
            coEvery { itemDao.getItems(any()) } returns emptyList()
            givenTranscodedEpisode()

            enqueuer().enqueue(uuid(2), USER)

            row.projectedBytes.shouldBeNull()
        }

    @Test
    fun `a film is never seeded, because it has no siblings`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(sourceBitRate = 3_000_000, runTimeTicks = HOUR_TICKS)))

            enqueuer().enqueue(uuid(1), USER)

            row.projectedBytes.shouldBeNull()
            coVerify(exactly = 0) { downloadDao.completedSiblings(any(), any(), any()) }
        }

    @Test
    fun `an original download is never seeded — its size is already exact`() =
        runTest {
            givenSiblings(uuid(31) to 200_000_000L)
            givenTranscodedEpisode()

            enqueuer().enqueue(uuid(2), USER)

            row.projectedBytes.shouldBeNull()
            coVerify(exactly = 0) { downloadDao.completedSiblings(any(), any(), any()) }
        }

    @Test
    fun `a remux-exact episode is never seeded either`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            givenSiblings(uuid(31) to 200_000_000L)
            givenTranscodedEpisode(streams = listOf(videoStream(bitRate = 4_000_000)))

            enqueuer().enqueue(uuid(2), USER)

            // A projection would replace an arithmetic answer with a guess, and flip the row's
            // wording from a plain figure to a hedged one.
            row.sizeIsExact shouldBe true
            row.projectedBytes.shouldBeNull()
        }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * The episode `uuid(2)` of *Westworld*, an hour long, at a bitrate every transcoded step has to
     * re-encode — so the ceiling is a ceiling, and seeding is allowed to improve on it.
     */
    private fun givenTranscodedEpisode(
        runTimeTicks: Long = HOUR_TICKS,
        streams: List<MediaStream> = emptyList(),
    ) {
        coEvery { api.getFullItems(listOf(uuid(2))) } returns
            AppResult.Success(
                listOf(episode(runTimeTicks = runTimeTicks, sourceBitRate = 40_000_000, streams = streams)),
            )
        coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
            AppResult.Success(listOf(series(), season()))
    }

    /**
     * Finished *Westworld* downloads, each an hour long and each having landed at the given size —
     * so every pair is a bytes-per-hour rate the seed can take a median of.
     */
    private fun givenSiblings(vararg landed: Pair<UUID, Long>) {
        coEvery { downloadDao.completedSiblings("Westworld", any(), any()) } returns
            landed.map { (id, bytes) ->
                DownloadFixtures.download(
                    itemId = id,
                    status = DownloadStatus.DOWNLOADED,
                    bytesDownloaded = bytes,
                    seriesName = "Westworld",
                )
            }
        coEvery { itemDao.getItems(any()) } returns
            landed.map { (id, _) -> cachedEpisode(id) }
    }

    private fun enqueuer() =
        DownloadEnqueuer(
            api = api,
            itemDao = itemDao,
            downloadDao = downloadDao,
            deleter = deleter,
            mapper = mapper,
            appPreferences = appPreferences,
            clock = clock,
        )

    private fun cachedItem(
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

    /** A finished sibling as the item cache holds it — the runtime is the field the seed needs. */
    private fun cachedEpisode(id: UUID) =
        ItemEntity(
            id = id,
            name = "Sibling",
            sortName = "Sibling",
            type = ItemType.EPISODE,
            source = ItemSource.DOWNLOAD,
            cachedAt = NOW,
            runTimeTicks = HOUR_TICKS,
            dto = "{}",
        )

    private companion object {
        val USER = uuid(99)

        /** One hour in `runTimeTicks` (100 ns each). */
        const val HOUR_TICKS = 36_000_000_000L
    }
}
