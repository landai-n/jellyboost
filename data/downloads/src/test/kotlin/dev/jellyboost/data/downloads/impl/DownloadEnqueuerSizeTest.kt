package dev.jellyboost.data.downloads.impl

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
import dev.jellyboost.data.downloads.DownloadFixtures.episode
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.season
import dev.jellyboost.data.downloads.DownloadFixtures.series
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.DownloadFixtures.videoStream
import dev.jellyboost.data.downloads.engine.SiblingSeeder
import io.kotest.matchers.collections.shouldContainExactly
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
 *
 * The same arithmetic then decides something larger than a number: a transcode estimated to weigh
 * what the source already does is not requested at all, and the row is written as an `ORIGINAL`
 * download instead (docs/features/download-quality.md, "When a transcode is not worth making").
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
                            // A 6 Mbps H.264 picture under a pile of lossless audio: 13,5 Mbps and
                            // 6 GB in total, which is well under HIGH's cap — so the source's own
                            // total bitrate is what the old estimate would have used, and it is the
                            // wrong number here. Dropping the audio tracks is also what makes this
                            // remux worth asking for at all, rather than a copy of a file the
                            // fall-back-to-original rule would send us to fetch whole.
                            sourceBitRate = 13_500_000,
                            sizeBytes = 6_075_000_000L,
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
                            // A 30 Mbps remux, above HIGH's cap, so the transcode is worth making
                            // and the row is not sent back to the original file instead.
                            sourceBitRate = SOURCE_BITRATE,
                            sizeBytes = SOURCE_BYTES_PER_HOUR,
                            runTimeTicks = HOUR_TICKS,
                            streams = listOf(videoStream(codec = "hevc")),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // `SupportedVideoCodecs` is our `videoCodec=h264` and the server's test is exact string
            // equality, so an HEVC source is re-encoded however small it is — and the figure is the
            // ordinary ceiling, here the quality's own cap since the source sits above it.
            row.sizeIsExact shouldBe false
            row.bytesTotal shouldBe 3_600L * DownloadQuality.HIGH.totalBitRate!! / 8
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
                            sourceBitRate = SOURCE_BITRATE,
                            sizeBytes = SOURCE_BYTES_PER_HOUR,
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
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = SOURCE_BITRATE,
                            sizeBytes = SOURCE_BYTES_PER_HOUR,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

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
                            sourceBitRate = SOURCE_BITRATE,
                            sizeBytes = SOURCE_BYTES_PER_HOUR,
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

    // ---- the audio sidecars of a transcode (offline multi-track, phase 2) ------------------------

    @Test
    fun `a re-encoded multi-language item is sized with the sidecars it will also fetch`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = SOURCE_BITRATE,
                            sizeBytes = SOURCE_BYTES_PER_HOUR,
                            runTimeTicks = HOUR_TICKS,
                            streams =
                                listOf(videoStream(codec = "hevc")) +
                                    (1..3).map { DownloadFixtures.audioStream(index = it) },
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // One language is baked into the transcode; the other two are separate AAC downloads,
            // and leaving them out understated a three-language film by ~330 MB.
            row.bytesTotal shouldBe HOUR_SECONDS * DownloadQuality.LOW.totalBitRate!! / 8 + TWO_SIDECARS
            row.sizeIsExact shouldBe false
        }

    @Test
    fun `a stream copy stops being exact as soon as there is a second language`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(multiLanguageRemuxSource(languages = 3)))

            enqueuer().enqueue(uuid(1), USER)

            // The *file* the server stream-copies is still arithmetic; the sidecars beside it are
            // transcodes of their own, so the item's figure is a ceiling again.
            row.bytesTotal shouldBe REMUX_BYTES + TWO_SIDECARS
            row.sizeIsExact shouldBe false
        }

    @Test
    fun `a single-language stream copy is still exact, and still just the file`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(multiLanguageRemuxSource(languages = 1)))

            enqueuer().enqueue(uuid(1), USER)

            row.bytesTotal shouldBe REMUX_BYTES
            row.sizeIsExact shouldBe true
        }

    // ---- a transcode that would not save space is downloaded as the original ---------------------

    @Test
    fun `a transcode that would not save space is downloaded as the original instead`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            // A 3,5 Mbps source asked for at LOW (3 Mbps + 192 kbps of audio): the transcode is
            // estimated at 91 % of the file the server already has. Nine percent is not worth an
            // encode, a lost resume and a generation of quality.
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = MODEST_BITRATE,
                            sizeBytes = MODEST_BYTES_PER_HOUR,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // Stamped `ORIGINAL`, so everything downstream follows from the row alone: the download
            // endpoint, a `Range`-resumable transfer, and the exact size the server measured.
            row.quality shouldBe DownloadQuality.ORIGINAL
            row.bytesTotal shouldBe MODEST_BYTES_PER_HOUR
            row.sizeIsExact shouldBe true
            row.projectedBytes.shouldBeNull()
        }

    @Test
    fun `a transcode saving just over a tenth of the file keeps the quality that was asked for`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            val ceiling = 3_600L * DownloadQuality.LOW.totalBitRate!! / Byte.SIZE_BITS
            // Exactly 0,89 of the original: one point on the useful side of the 0,9 threshold, so
            // this pins the boundary rather than the comfortable case above it.
            val originalBytes = (ceiling / 0.89).toLong()
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = (originalBytes * Byte.SIZE_BITS / 3_600L).toInt(),
                            sizeBytes = originalBytes,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            row.quality shouldBe DownloadQuality.LOW
            row.bytesTotal shouldBe ceiling
            row.sizeIsExact shouldBe false
        }

    @Test
    fun `a stream copy that weighs what the original does is downloaded as the original`() =
        runTest {
            downloadQuality.value = DownloadQuality.HIGH
            // 1080p H.264 at 6 Mbps with one ordinary audio track: HIGH would copy the video
            // through untouched and re-encode only the audio, landing within half a percent of the
            // source file. This is the case the rule exists for — paying the server for a copy of
            // what it already has.
            val videoBitRate = 6_000_000
            val sourceBitRate = videoBitRate + 220_000
            val originalBytes = 3_600L * sourceBitRate / Byte.SIZE_BITS
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = sourceBitRate,
                            sizeBytes = originalBytes,
                            runTimeTicks = HOUR_TICKS,
                            streams = listOf(videoStream(bitRate = videoBitRate, height = 1080)),
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // The remux figure the comparison used (2 786 400 000) is *not* what the row carries:
            // the size that goes with the quality actually stamped is the one that is stored.
            row.quality shouldBe DownloadQuality.ORIGINAL
            row.bytesTotal shouldBe originalBytes
            row.sizeIsExact shouldBe true
        }

    @Test
    fun `a source the server reports no size for keeps the quality the user chose`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = MODEST_BITRATE,
                            sizeBytes = null,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            // The same source as the first case in this section, minus the one figure the
            // comparison needs. The preference is the default and a guess is not grounds for
            // overriding it, so the transcode the user asked for is what is queued.
            row.quality shouldBe DownloadQuality.LOW
            row.bytesTotal shouldBe 3_600L * DownloadQuality.LOW.totalBitRate!! / Byte.SIZE_BITS
        }

    @Test
    fun `an item with no runtime keeps the quality the user chose, having nothing to compare`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            // A tiny file a transcode could not possibly beat — but with no runtime there is no
            // estimate to weigh it against, and half a comparison decides nothing.
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(listOf(movie(sizeBytes = 1_000_000L, runTimeTicks = null)))

            enqueuer().enqueue(uuid(1), USER)

            row.quality shouldBe DownloadQuality.LOW
            row.bytesTotal shouldBe 0L
        }

    @Test
    fun `an original download is stamped as asked, whatever a transcode would have weighed`() =
        runTest {
            // The preference is `ORIGINAL` throughout this test: the rule only ever moves a row
            // *towards* the original, so there is nothing here for it to reconsider.
            coEvery { api.getFullItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie(
                            sourceBitRate = SOURCE_BITRATE,
                            sizeBytes = SOURCE_BYTES_PER_HOUR,
                            runTimeTicks = HOUR_TICKS,
                        ),
                    ),
                )

            enqueuer().enqueue(uuid(1), USER)

            row.quality shouldBe DownloadQuality.ORIGINAL
            row.bytesTotal shouldBe SOURCE_BYTES_PER_HOUR
            row.sizeIsExact shouldBe true
        }

    @Test
    fun `one episode of a season falls back to the original while another keeps the transcode`() =
        runTest {
            downloadQuality.value = DownloadQuality.LOW
            givenSeasonOf(
                // A 30 Mbps remux: LOW saves 92 % of it, and is worth every cycle it costs.
                episode(
                    id = uuid(2),
                    runTimeTicks = HOUR_TICKS,
                    sourceBitRate = SOURCE_BITRATE,
                    sizeBytes = SOURCE_BYTES_PER_HOUR,
                ),
                // A 3,5 Mbps episode of the same season: LOW would save nine percent of it.
                episode(
                    id = uuid(3),
                    episodeNumber = 3,
                    runTimeTicks = HOUR_TICKS,
                    sourceBitRate = MODEST_BITRATE,
                    sizeBytes = MODEST_BYTES_PER_HOUR,
                ),
            )

            enqueuer().enqueue(uuid(11), USER)

            // One tap, one preference — but the decision is taken per episode, because the episodes
            // of one season are not the same file twice.
            rows.map { it.itemId to it.quality } shouldContainExactly
                listOf(uuid(2) to DownloadQuality.LOW, uuid(3) to DownloadQuality.ORIGINAL)
            rows.map { it.sizeIsExact } shouldContainExactly listOf(false, true)
            rows.map { it.bytesTotal } shouldContainExactly
                listOf(3_600L * DownloadQuality.LOW.totalBitRate!! / Byte.SIZE_BITS, MODEST_BYTES_PER_HOUR)
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
     *
     * Its file size is the one that bitrate implies over that runtime, which is also what keeps
     * these rows transcoded at all: a source this far above every cap has a great deal of space to
     * save, so the fall-back-to-original rule never fires on it.
     */
    private fun givenTranscodedEpisode(
        runTimeTicks: Long = HOUR_TICKS,
        streams: List<MediaStream> = emptyList(),
    ) {
        coEvery { api.getFullItems(listOf(uuid(2))) } returns
            AppResult.Success(
                listOf(
                    episode(
                        runTimeTicks = runTimeTicks,
                        sizeBytes = runTimeTicks / TICKS_PER_SECOND * SOURCE_BITRATE / Byte.SIZE_BITS,
                        sourceBitRate = SOURCE_BITRATE,
                        streams = streams,
                    ),
                ),
            )
        coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
            AppResult.Success(listOf(series(), season()))
    }

    /**
     * An hour of 6 Mbps H.264 the server will stream-copy at `HIGH`, carrying [languages] audio
     * tracks — the source shape both halves of the sidecar-exactness rule are asked about.
     *
     * Its file size is the pile of lossless audio the original holds, which is what keeps the row
     * transcoded: dropping those tracks is the whole saving, so the fall-back-to-original rule
     * never fires on it.
     */
    private fun multiLanguageRemuxSource(languages: Int) =
        movie(
            sourceBitRate = 13_500_000,
            sizeBytes = 6_075_000_000L,
            runTimeTicks = HOUR_TICKS,
            streams =
                listOf(videoStream(bitRate = REMUX_VIDEO_BITRATE, height = 1080)) +
                    (1..languages).map { DownloadFixtures.audioStream(index = it) },
        )

    /** The season `uuid(11)` of *Westworld*, as the server answers for it, with these episodes. */
    private fun givenSeasonOf(vararg episodes: BaseItemDto) {
        coEvery { api.getFullItems(listOf(uuid(11))) } returns AppResult.Success(listOf(season()))
        coEvery { api.getEpisodeIds(uuid(10), uuid(11)) } returns AppResult.Success(episodes.map { it.id })
        coEvery { api.getFullItems(episodes.map { it.id }) } returns AppResult.Success(episodes.toList())
        // The season is already being cached by the expansion, so only the series is fetched.
        coEvery { api.getFullItems(listOf(uuid(10))) } returns AppResult.Success(listOf(series()))
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
            // The real seeder over the same mocked DAOs: the median it computes *is* what these
            // tests are about, so stubbing it would leave the arithmetic untested.
            seeder = SiblingSeeder(downloadDao = downloadDao, itemDao = itemDao, clock = clock),
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

        /** The same hour in seconds — the unit every estimate below is arithmetic in. */
        const val HOUR_SECONDS = 3_600L

        /** The video bitrate of the source `multiLanguageRemuxSource` builds. */
        const val REMUX_VIDEO_BITRATE = 6_000_000

        /** What that source's stream copy weighs: its video track plus the one baked-in AAC track. */
        const val REMUX_BYTES = HOUR_SECONDS * (REMUX_VIDEO_BITRATE + DownloadQuality.AUDIO_BITRATE) / 8

        /** Two extra languages at [DownloadQuality.AUDIO_BITRATE], for an hour. */
        const val TWO_SIDECARS = 2 * HOUR_SECONDS * DownloadQuality.AUDIO_BITRATE / 8

        /** A `runTimeTicks` tick is 100 ns, so there are ten million of them in a second. */
        const val TICKS_PER_SECOND = 10_000_000L

        /**
         * A 30 Mbps source — above every quality step's cap, so a transcode of it genuinely saves
         * space and the row is not sent back to the original file (`DownloadEnqueuer.planQuality`).
         */
        const val SOURCE_BITRATE = 30_000_000

        /** What [SOURCE_BITRATE] weighs over an hour: the file size that bitrate implies. */
        const val SOURCE_BYTES_PER_HOUR = 3_600L * SOURCE_BITRATE / Byte.SIZE_BITS

        /**
         * A 3,5 Mbps source — barely above `LOW`'s own ceiling, so a `LOW` transcode of it would
         * save about nine percent and is not worth making.
         */
        const val MODEST_BITRATE = 3_500_000

        /** What [MODEST_BITRATE] weighs over an hour. */
        const val MODEST_BYTES_PER_HOUR = 3_600L * MODEST_BITRATE / Byte.SIZE_BITS
    }
}
