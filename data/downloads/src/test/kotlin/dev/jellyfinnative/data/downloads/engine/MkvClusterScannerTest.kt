package dev.jellyfinnative.data.downloads.engine

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MkvClusterScanner] — the live size projection's only source of evidence
 * (docs/notes/download-size-estimation.md).
 *
 * Everything here is built out of **synthetic EBML bytes** rather than a recorded file, because the
 * cases worth pinning are the ones a recorded file cannot contain on demand: an element cut in half
 * by a chunk boundary, a four-byte cluster id occurring by chance inside frame data, a timestamp
 * that goes backwards. [Ebml] below builds exactly the shapes Matroska defines, so a test that says
 * "this is a valid cluster" is asserting against the format and not against the parser's habits.
 */
class MkvClusterScannerTest {
    // ---- the ordinary case -----------------------------------------------------------------------

    @Test
    fun `nothing is known before the first cluster arrives`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.header() + Ebml.timestampScale(1_000_000L))

        // `null`, not zero: "no evidence yet" is what keeps a row saying "up to X" rather than "~X".
        scanner.mediaMillisReceived.shouldBeNull()
    }

    @Test
    fun `a cluster timestamp is read out of a single chunk`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.header() + Ebml.cluster(ticks = 5_000L))

        scanner.mediaMillisReceived shouldBe 5_000L
    }

    @Test
    fun `the newest of several clusters is what counts`() {
        val scanner = MkvClusterScanner()

        scanner.consume(
            Ebml.header() +
                Ebml.cluster(ticks = 5_000L) +
                Ebml.cluster(ticks = 10_000L) +
                Ebml.cluster(ticks = 15_000L),
        )

        scanner.mediaMillisReceived shouldBe 15_000L
    }

    @Test
    fun `a timestamp wider than one byte is decoded big-endian`() {
        val scanner = MkvClusterScanner()

        // 3 600 000 ms — an hour in, which needs three bytes.
        scanner.consume(Ebml.cluster(ticks = 3_600_000L, ticksWidth = 3))

        scanner.mediaMillisReceived shouldBe 3_600_000L
    }

    // ---- the CRC-32 ffmpeg puts in front of the timestamp ----------------------------------------

    @Test
    fun `a cluster whose first child is a CRC-32 is read, because that is what ffmpeg writes`() {
        val scanner = MkvClusterScanner()

        // The shape of a real transcode off the server: every cluster opens with `BF 84 <crc>` and
        // only then the timestamp. Requiring `0xE7` to come first made this read zero clusters and
        // left every transcoded row stuck on "up to X" for its whole download.
        scanner.consume(Ebml.header() + Ebml.cluster(ticks = 4_864L, crc = true))

        scanner.mediaMillisReceived shouldBe 4_864L
    }

    @Test
    fun `the newest of several CRC-32 clusters is what counts`() {
        val scanner = MkvClusterScanner()

        scanner.consume(
            Ebml.header() +
                Ebml.cluster(ticks = 5_000L, crc = true) +
                Ebml.cluster(ticks = 10_000L, crc = true),
        )

        scanner.mediaMillisReceived shouldBe 10_000L
    }

    @Test
    fun `a CRC-32 followed by something other than the timestamp is still rejected`() {
        val scanner = MkvClusterScanner()
        // Allowing `CRC-32` through must not become "allow anything through": `0xA3` is SimpleBlock,
        // and a cluster that opens with a CRC and then a block is not one we can time.
        // Size `0x8C`, then `BF 84 <4>` — a well-formed CRC — and then a SimpleBlock where the
        // timestamp should be.
        val notTimestamp =
            Ebml.CLUSTER_ID +
                byteArrayOf(0x8C.toByte(), 0xBF.toByte(), 0x84.toByte(), 0x01, 0x02, 0x03, 0x04) +
                byteArrayOf(0xA3.toByte(), 0x84.toByte(), 0x05, 0x06, 0x07, 0x08)

        scanner.consume(Ebml.cluster(ticks = 4_000L) + notTimestamp)

        scanner.mediaMillisReceived shouldBe 4_000L
    }

    // ---- chunk boundaries ------------------------------------------------------------------------

    @Test
    fun `a cluster id split across two chunks is still found`() {
        val stream = Ebml.header() + Ebml.cluster(ticks = 7_000L)
        // Cut in the middle of the four id bytes.
        val cut = Ebml.header().size + 2
        val scanner = MkvClusterScanner()

        scanner.consume(stream, 0, cut)
        scanner.consume(stream, cut, stream.size - cut)

        scanner.mediaMillisReceived shouldBe 7_000L
    }

    @Test
    fun `a timestamp element split across two chunks is still found`() {
        val stream = Ebml.header() + Ebml.cluster(ticks = 7_000L, ticksWidth = 4)
        // Past the id and the cluster size, inside the timestamp's own bytes.
        val cut = Ebml.header().size + 7
        val scanner = MkvClusterScanner()

        scanner.consume(stream, 0, cut)
        scanner.consume(stream, cut, stream.size - cut)

        scanner.mediaMillisReceived shouldBe 7_000L
    }

    @Test
    fun `the same timestamp is found wherever the chunk boundary falls`() {
        val stream = Ebml.header() + Ebml.timestampScale(1_000_000L) + Ebml.cluster(ticks = 9_000L, ticksWidth = 4)

        // The carry buffer's whole job, checked at every possible cut rather than at a lucky one.
        for (cut in 1 until stream.size) {
            val scanner = MkvClusterScanner()
            scanner.consume(stream, 0, cut)
            scanner.consume(stream, cut, stream.size - cut)

            scanner.mediaMillisReceived shouldBe 9_000L
        }
    }

    @Test
    fun `a CRC-32 cluster is found wherever the chunk boundary falls`() {
        val stream =
            Ebml.header() + Ebml.timestampScale(1_000_000L) +
                Ebml.cluster(ticks = 9_000L, ticksWidth = 4, crc = true)

        // The CRC pushes the timestamp four bytes further into the element, so the carry buffer has
        // to cover a longer candidate than it did before it was allowed for.
        for (cut in 1 until stream.size) {
            val scanner = MkvClusterScanner()
            scanner.consume(stream, 0, cut)
            scanner.consume(stream, cut, stream.size - cut)

            scanner.mediaMillisReceived shouldBe 9_000L
        }
    }

    @Test
    fun `a stream fed one byte at a time reads the same as one fed whole`() {
        val stream = Ebml.header() + Ebml.cluster(ticks = 1_000L) + Ebml.cluster(ticks = 2_000L)
        val scanner = MkvClusterScanner()

        for (index in stream.indices) scanner.consume(stream, index, 1)

        scanner.mediaMillisReceived shouldBe 2_000L
    }

    // ---- false positives -------------------------------------------------------------------------

    @Test
    fun `the cluster id occurring inside payload data is rejected`() {
        val scanner = MkvClusterScanner()
        // A real cluster, then the same four bytes buried in frame data followed by nothing that
        // parses. Four bytes recur by chance about every 4 GB; the download must survive it.
        val garbage =
            Ebml.CLUSTER_ID + byteArrayOf(0x3C, 0x7F, 0x11, 0x02, 0x49.toByte(), 0x00, 0x00, 0x00)

        scanner.consume(Ebml.cluster(ticks = 4_000L) + garbage)

        scanner.mediaMillisReceived shouldBe 4_000L
    }

    @Test
    fun `a cluster id followed by an invalid size varint is rejected`() {
        val scanner = MkvClusterScanner()
        // A `0x00` lead byte encodes a varint wider than the eight bytes Matroska allows.
        val garbage = Ebml.CLUSTER_ID + byteArrayOf(0x00, 0x00, 0xE7.toByte(), 0x81.toByte(), 0x64)

        scanner.consume(Ebml.cluster(ticks = 4_000L) + garbage)

        scanner.mediaMillisReceived shouldBe 4_000L
    }

    @Test
    fun `a cluster whose first child is not the timestamp is rejected`() {
        val scanner = MkvClusterScanner()
        // `0xA3` is SimpleBlock — a legal cluster child, just not the first one, and a shape a
        // random four-byte hit is far more likely to produce than a well-formed timestamp.
        val notFirst =
            Ebml.CLUSTER_ID + byteArrayOf(0x88.toByte(), 0xA3.toByte(), 0x84.toByte(), 0x01, 0x02, 0x03, 0x04)

        scanner.consume(Ebml.cluster(ticks = 4_000L) + notFirst)

        scanner.mediaMillisReceived shouldBe 4_000L
    }

    @Test
    fun `a cluster claiming an implausible size is rejected`() {
        val scanner = MkvClusterScanner()
        // Size `1`: too small to hold even the timestamp element that follows it, so the match is
        // structurally impossible however well the later bytes line up.
        val tooSmall = Ebml.CLUSTER_ID + byteArrayOf(0x81.toByte(), 0xE7.toByte(), 0x81.toByte(), 0x64)

        scanner.consume(Ebml.cluster(ticks = 4_000L) + tooSmall)

        scanner.mediaMillisReceived shouldBe 4_000L
    }

    @Test
    fun `a timestamp declaring a length Matroska cannot store is rejected`() {
        val scanner = MkvClusterScanner()
        // `0x89` would be a nine-byte integer; the widest Matroska has is eight.
        val tooWide =
            Ebml.CLUSTER_ID + byteArrayOf(0x8C.toByte(), 0xE7.toByte(), 0x89.toByte()) + ByteArray(9)

        scanner.consume(Ebml.cluster(ticks = 4_000L) + tooWide)

        scanner.mediaMillisReceived shouldBe 4_000L
    }

    @Test
    fun `an unknown-size cluster is accepted, because a live mux writes one`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.cluster(ticks = 6_000L, unknownSize = true))

        scanner.mediaMillisReceived shouldBe 6_000L
    }

    // ---- monotonicity ----------------------------------------------------------------------------

    @Test
    fun `a timestamp that goes backwards is ignored`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.cluster(ticks = 20_000L))
        scanner.consume(Ebml.cluster(ticks = 3_000L))

        // ffmpeg writes the file forwards, so a lower timestamp is a false positive — and letting
        // it through would make the projection jump upwards and the progress bar retreat.
        scanner.mediaMillisReceived shouldBe 20_000L
    }

    @Test
    fun `a repeated timestamp is harmless, which is what makes re-scanning the carry safe`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.cluster(ticks = 8_000L))
        scanner.consume(Ebml.cluster(ticks = 8_000L))

        scanner.mediaMillisReceived shouldBe 8_000L
    }

    @Test
    fun `a timestamp beyond any real runtime is rejected`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.cluster(ticks = 5_000L))
        // ~2.7 years at 1 ms per tick: not a media timestamp, so it is a bad parse.
        scanner.consume(Ebml.cluster(ticks = 0x0000_1FFF_FFFF_FFFFL, ticksWidth = 8))

        scanner.mediaMillisReceived shouldBe 5_000L
    }

    // ---- TimestampScale --------------------------------------------------------------------------

    @Test
    fun `the default scale of one millisecond per tick applies when Segment Info says nothing`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.header() + Ebml.cluster(ticks = 12_000L, ticksWidth = 2))

        scanner.mediaMillisReceived shouldBe 12_000L
    }

    @Test
    fun `a declared scale is applied to every later timestamp`() {
        val scanner = MkvClusterScanner()

        // 100 000 ns per tick: ten ticks to the millisecond.
        scanner.consume(Ebml.header() + Ebml.timestampScale(100_000L) + Ebml.cluster(ticks = 50_000L, ticksWidth = 3))

        scanner.mediaMillisReceived shouldBe 5_000L
    }

    @Test
    fun `a scale byte pattern occurring after the first cluster cannot move the clock`() {
        val scanner = MkvClusterScanner()

        scanner.consume(Ebml.cluster(ticks = 4_000L))
        // Segment Info comes before the clusters, so this can only be frame data that happens to
        // contain the three id bytes — and taking it would rescale every timestamp already read.
        scanner.consume(Ebml.timestampScale(100_000L) + Ebml.cluster(ticks = 8_000L))

        scanner.mediaMillisReceived shouldBe 8_000L
    }

    @Test
    fun `an implausible scale is ignored in favour of the default`() {
        val scanner = MkvClusterScanner()

        // Ten seconds per tick is not a Matroska file we can reason about.
        scanner.consume(Ebml.timestampScale(10_000_000_000L) + Ebml.cluster(ticks = 3_000L))

        scanner.mediaMillisReceived shouldBe 3_000L
    }

    // ---- the bytes ------------------------------------------------------------------------------

    /** Builds the Matroska shapes the scanner looks for, from the format's own rules. */
    private object Ebml {
        val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        private val TIMESTAMP_SCALE_ID = byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte())
        private const val TIMESTAMP_ID = 0xE7.toByte()
        private const val CRC32_ID = 0xBF.toByte()

        /** Plausible leading bytes — an EBML header and a Segment id — that are not what we scan for. */
        fun header(): ByteArray =
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte()) +
                ByteArray(0x1F) { (it * 7).toByte() } +
                byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0xFF.toByte())

        /** `TimestampScale` carrying [nanos] nanoseconds per tick. */
        fun timestampScale(nanos: Long): ByteArray {
            val value = unsigned(nanos, width = 4)
            return TIMESTAMP_SCALE_ID + byteArrayOf((0x80 or value.size).toByte()) + value
        }

        /**
         * One cluster: the id, its size, and a `Timestamp` child of [ticks].
         *
         * @param unknownSize writes the all-ones size a stream being muxed live uses.
         * @param crc writes the four-byte `CRC-32` in front of the timestamp that ffmpeg's muxer
         *   emits by default — the real shape, which the synthetic default here is not.
         */
        fun cluster(
            ticks: Long,
            ticksWidth: Int = 2,
            unknownSize: Boolean = false,
            crc: Boolean = false,
        ): ByteArray {
            val value = unsigned(ticks, ticksWidth)
            val crc32 = byteArrayOf(CRC32_ID, 0x84.toByte(), 0x0B, 0xAD.toByte(), 0x0C, 0x0D)
            val prefix = if (crc) crc32 else ByteArray(0)
            val body = prefix + byteArrayOf(TIMESTAMP_ID, (0x80 or value.size).toByte()) + value
            val size = if (unknownSize) byteArrayOf(0xFF.toByte()) else byteArrayOf((0x80 or body.size).toByte())
            return CLUSTER_ID + size + body
        }

        /** [value] as a big-endian unsigned integer of exactly [width] bytes. */
        private fun unsigned(
            value: Long,
            width: Int,
        ): ByteArray {
            val bytes = ByteArray(width)
            var remaining = value
            for (index in width - 1 downTo 0) {
                bytes[index] = (remaining and 0xFF).toByte()
                remaining = remaining shr Byte.SIZE_BITS
            }
            return bytes
        }
    }
}
