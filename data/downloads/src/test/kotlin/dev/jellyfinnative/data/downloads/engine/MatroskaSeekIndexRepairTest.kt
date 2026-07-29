package dev.jellyfinnative.data.downloads.engine

import dev.jellyfinnative.data.downloads.engine.MatroskaSeekIndexRepair.Outcome
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [MatroskaSeekIndexRepair] — what makes a transcoded download seekable.
 *
 * Everything here is built out of **synthetic Matroska bytes** by [Mkv], which reproduces the shape
 * a live ffmpeg mux lands on the device and which a real `(low).mkv` pulled off the test tablet
 * confirmed: an unknown-size Segment, a 152-byte Void where the `SeekHead` should be, an 11-byte
 * Void inside `Info` where `Duration` should be, and the `Cues` appended at the very end of the file
 * where nothing points at them.
 *
 * The result is read back with [Reader], a walker written independently of the one under test, so a
 * passing assertion says "this file now parses as Matroska, with a `SeekHead` naming the `Cues`"
 * rather than "the code agrees with itself".
 *
 * The property pinned hardest is the one that protects a gigabyte on a user's tablet: **a refusal
 * must not change a single byte.** Every rejection case below asserts the file is identical after.
 */
class MatroskaSeekIndexRepairTest {
    @TempDir
    lateinit var directory: File

    private val repair = MatroskaSeekIndexRepair()

    // ---- the transcode this exists for ------------------------------------------------------------

    @Test
    fun `a live-muxed transcode gains a SeekHead pointing at its Cues`() {
        val fixture = Mkv.transcode()
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        // Nothing moved: every cluster offset the Cues already carry is still correct.
        patched.size shouldBe fixture.bytes.size

        val seekHead = Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        seekHead.start shouldBe fixture.segmentContent
        Reader(patched).cuesPosition(seekHead) shouldBe fixture.cuesOffset - fixture.segmentContent
    }

    @Test
    fun `the header still chains all the way to the first cluster afterwards`() {
        val file = write(Mkv.transcode())

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS)

        // What is left of the reserved Void has to be a legal Void of exactly the right size, or the
        // walk lands in the middle of Info and the file stops being Matroska.
        Reader(file.readBytes()).topLevelIds() shouldBe
            listOf(
                Reader.ID_SEEK_HEAD,
                Reader.ID_VOID,
                Reader.ID_INFO,
                Reader.ID_TRACKS,
                Reader.ID_CLUSTER,
                Reader.ID_CLUSTER,
                Reader.ID_CUES,
            )
    }

    @Test
    fun `every byte outside the two reserved Voids is untouched`() {
        val fixture = Mkv.transcode()
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS)

        val patched = file.readBytes()
        unchanged(fixture.bytes, patched, 0, fixture.segmentContent)
        unchanged(fixture.bytes, patched, fixture.reservedVoidEnd, fixture.infoVoid)
        unchanged(fixture.bytes, patched, fixture.infoVoidEnd, fixture.bytes.size.toLong())
    }

    // ---- the Duration ffmpeg also could not write --------------------------------------------------

    @Test
    fun `the runtime is written into the Void reserved for Duration`() {
        val file = write(Mkv.transcode())

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS)

        // TimestampScale is one millisecond per tick, so ticks and milliseconds coincide.
        Reader(file.readBytes()).duration() shouldBe RUNTIME_MILLIS.toDouble()
    }

    @Test
    fun `a coarser TimestampScale is divided out of the Duration`() {
        // One second per tick: a 1 380 000 ms runtime is 1 380 ticks.
        val file = write(Mkv.transcode(timestampScaleNanos = 1_000_000_000L))

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS)

        Reader(file.readBytes()).duration() shouldBe 1_380.0
    }

    @Test
    fun `an item with no known runtime still gets its seek index`() {
        val file = write(Mkv.transcode())

        repair.ensureSeekable(file, runtimeMillis = 0L) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        // Nothing invented: no runtime means no Duration, and the Void stays a Void.
        Reader(patched).duration().shouldBeNull()
    }

    @Test
    fun `a Duration that is already there is not overwritten`() {
        val file = write(Mkv.transcode(existingDurationTicks = 99.0))

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        Reader(file.readBytes()).duration() shouldBe 99.0
    }

    @Test
    fun `no room for a Duration still leaves the file seekable`() {
        // Four bytes of Void inside Info: too small for the eleven a 64-bit Duration needs.
        val file = write(Mkv.transcode(infoVoidBytes = 4))

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        Reader(patched).duration().shouldBeNull()
    }

    // ---- the files that must be left exactly alone -------------------------------------------------

    @Test
    fun `a file that already has a SeekHead is not touched`() {
        val fixture = Mkv.transcode(seekHeadInsteadOfVoid = true)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.ALREADY_INDEXED

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `a transcode whose Cues never arrived is left alone`() {
        // An interrupted transfer: clusters, and then nothing. There is no index to point at.
        val fixture = Mkv.transcode(cues = false)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.NO_CUES

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `a header with no room for a SeekHead is left alone`() {
        val fixture = Mkv.transcode(reservedVoidBytes = 8)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.NO_ROOM

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `something that is not Matroska is left alone`() {
        val original = ByteArray(4_096) { (it % 251).toByte() }
        val file = File(directory, "media.mkv").apply { writeBytes(original) }

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.NOT_MATROSKA

        file.readBytes().contentEquals(original) shouldBe true
    }

    @Test
    fun `an empty file is left alone`() {
        val file = File(directory, "media.mkv").apply { writeBytes(ByteArray(0)) }

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.NOT_MATROSKA

        file.length() shouldBe 0L
    }

    // ---- repeating it, which is what running from the playback path means --------------------------

    @Test
    fun `repairing twice is a no-op the second time`() {
        val file = write(Mkv.transcode())

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED
        val once = file.readBytes()

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.ALREADY_INDEXED

        file.readBytes().contentEquals(once) shouldBe true
    }

    // ---- finding the Cues ---------------------------------------------------------------------------

    @Test
    fun `Cues followed by Tags are still found`() {
        val fixture = Mkv.transcode(tagsAfterCues = true)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        val seekHead = Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        Reader(patched).cuesPosition(seekHead) shouldBe fixture.cuesOffset - fixture.segmentContent
    }

    @Test
    fun `a stray Cues id inside cluster data is not mistaken for the index`() {
        // The four bytes `1C 53 BB 6B` sitting in a frame, with a well-formed size behind them. It is
        // rejected because the chain it starts does not land on the end of the file.
        val fixture = Mkv.transcode(decoyCuesInCluster = true)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        val seekHead = Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        Reader(patched).cuesPosition(seekHead) shouldBe fixture.cuesOffset - fixture.segmentContent
    }

    // ---- the header a real server actually produced ------------------------------------------------

    @Test
    fun `the header ffmpeg landed on the test tablet is indexed at the offsets it reserved`() {
        // `ffmpeg-transcode-header.bin` is the first 291 bytes of a real `(low).mkv` pulled off the
        // test tablet: EBML header, an unknown-size Segment, the 152-byte Void ffmpeg reserved for a
        // `SeekHead` it could not come back to write, and an `Info` carrying `TimestampScale` and the
        // 11-byte Void reserved for `Duration`. Clusters and `Cues` are synthetic — only the header
        // shape is what this pins, and it is the shape the whole repair is aimed at.
        val header = checkNotNull(javaClass.getResourceAsStream("/ffmpeg-transcode-header.bin")).readBytes()
        val cluster = Mkv.cluster()
        val cuesOffset = header.size + cluster.size
        val file = File(directory, "media.mkv").apply { writeBytes(header + cluster + Mkv.cues()) }

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        // The SeekHead goes exactly where the Void was, at the Segment's first content byte, and is
        // spelled out here byte for byte because those 26 bytes are the entire fix.
        patched.copyOfRange(REAL_SEGMENT_CONTENT, REAL_SEGMENT_CONTENT + SEEK_HEAD_BYTES).toHex() shouldBe
            "114d9b74954dbb9253ab841c53bb6b53ac88" + "%016x".format(cuesOffset - REAL_SEGMENT_CONTENT)
        // And the rest of the reserved 152 bytes stays a Void, so the walk still reaches Info.
        patched[REAL_SEGMENT_CONTENT + SEEK_HEAD_BYTES] shouldBe 0xEC.toByte()

        Reader(patched).topLevelIds() shouldBe
            listOf(Reader.ID_SEEK_HEAD, Reader.ID_VOID, Reader.ID_INFO, Reader.ID_CLUSTER, Reader.ID_CUES)
        // TimestampScale in that Info is 1 000 000 ns, so the Duration is the runtime in milliseconds.
        Reader(patched).duration() shouldBe RUNTIME_MILLIS.toDouble()
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun write(fixture: Mkv.Fixture): File = File(directory, "media.mkv").apply { writeBytes(fixture.bytes) }

    private fun unchanged(
        original: ByteArray,
        patched: ByteArray,
        from: Long,
        until: Long,
    ) {
        patched
            .copyOfRange(from.toInt(), until.toInt())
            .contentEquals(original.copyOfRange(from.toInt(), until.toInt())) shouldBe true
    }

    /**
     * Builds the byte shape a Jellyfin transcode actually lands on the device, and reports the
     * offsets the assertions need rather than making them recompute the arithmetic.
     */
    private object Mkv {
        /** The Void ffmpeg reserves for the `SeekHead` it can never come back to write. */
        const val RESERVED_VOID_BYTES = 152

        /** The Void it reserves inside `Info` for `Duration`, sized for a 64-bit float. */
        const val INFO_VOID_BYTES = 11

        class Fixture(
            val bytes: ByteArray,
            val segmentContent: Long,
            val reservedVoidEnd: Long,
            val infoVoid: Long,
            val infoVoidEnd: Long,
            val cuesOffset: Long,
        )

        @Suppress("LongParameterList")
        fun transcode(
            reservedVoidBytes: Int = RESERVED_VOID_BYTES,
            infoVoidBytes: Int = INFO_VOID_BYTES,
            timestampScaleNanos: Long = 1_000_000L,
            existingDurationTicks: Double? = null,
            cues: Boolean = true,
            tagsAfterCues: Boolean = false,
            seekHeadInsteadOfVoid: Boolean = false,
            decoyCuesInCluster: Boolean = false,
        ): Fixture {
            val header = element(ID_EBML, ByteArray(35) { 0x22 }) + ID_SEGMENT + UNKNOWN_SIZE
            val segmentContent = header.size.toLong()

            val reserved =
                if (seekHeadInsteadOfVoid) {
                    element(ID_SEEK_HEAD, ByteArray(reservedVoidBytes - HEADER_BYTES))
                } else {
                    void(reservedVoidBytes)
                }
            val infoBody =
                void(infoVoidBytes) +
                    element(ID_TIMESTAMP_SCALE, unsigned(timestampScaleNanos)) +
                    (existingDurationTicks?.let { element(ID_DURATION, bigEndian(it.toRawBits())) } ?: ByteArray(0))

            val prefix =
                header + reserved + element(ID_INFO, infoBody) +
                    element(ID_TRACKS, ByteArray(64) { 0x11 }) +
                    element(ID_CLUSTER, clusterPayload(decoyCuesInCluster)) +
                    element(ID_CLUSTER, ByteArray(4_096) { (it % 97).toByte() })

            val tail =
                when {
                    !cues -> ByteArray(0)
                    tagsAfterCues -> cues() + element(ID_TAGS, ByteArray(64) { 0x44 })
                    else -> cues()
                }

            val reservedVoidEnd = segmentContent + reserved.size
            val infoVoid = reservedVoidEnd + HEADER_BYTES
            return Fixture(
                bytes = prefix + tail,
                segmentContent = segmentContent,
                reservedVoidEnd = reservedVoidEnd,
                infoVoid = infoVoid,
                infoVoidEnd = infoVoid + infoVoidBytes,
                cuesOffset = prefix.size.toLong(),
            )
        }

        /** One cluster, to hang off a real ffmpeg header. */
        fun cluster(): ByteArray = element(ID_CLUSTER, ByteArray(4_096) { (it % 97).toByte() })

        /** The `Cues` ffmpeg appends in its trailer, where nothing points at them. */
        fun cues(): ByteArray = element(ID_CUES, ByteArray(512) { 0x33 })

        /** Cluster bytes, optionally with the `Cues` id buried in them behind a well-formed size. */
        private fun clusterPayload(decoy: Boolean): ByteArray {
            val payload = ByteArray(2_048) { (it % 89).toByte() }
            if (decoy) {
                ID_CUES.copyInto(payload, DECOY_OFFSET)
                // A one-byte size of 16: legal, and chains nowhere near the end of the file.
                payload[DECOY_OFFSET + ID_CUES.size] = 0x90.toByte()
            }
            return payload
        }

        private fun element(
            id: ByteArray,
            payload: ByteArray,
        ): ByteArray = id + wideLength(payload.size.toLong()) + payload

        private fun void(total: Int): ByteArray =
            when {
                total < VOID_LONG_FORM_MIN ->
                    byteArrayOf(ID_VOID, (0x80 or (total - 2)).toByte()) + ByteArray(total - 2)

                else -> byteArrayOf(ID_VOID) + wideLength((total - 9).toLong()) + ByteArray(total - 9)
            }

        private fun unsigned(value: Long): ByteArray {
            var remaining = value
            val bytes = mutableListOf<Byte>()
            while (remaining > 0L) {
                bytes.add(0, (remaining and 0xFF).toByte())
                remaining = remaining ushr Byte.SIZE_BITS
            }
            return bytes.toByteArray()
        }

        /** The eight-byte EBML length form, so every element header in a fixture is the same width. */
        private fun wideLength(value: Long): ByteArray = bigEndian(value).also { it[0] = 0x01 }

        private fun bigEndian(value: Long): ByteArray =
            ByteArray(Long.SIZE_BYTES) { (value ushr ((Long.SIZE_BYTES - 1 - it) * Byte.SIZE_BITS)).toByte() }

        /** 4 bytes of id plus the 8-byte length form: the header every fixture element carries. */
        private const val HEADER_BYTES = 12

        private const val VOID_LONG_FORM_MIN = 10
        private const val DECOY_OFFSET = 700

        private val ID_EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        private val ID_SEGMENT = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
        private val ID_SEEK_HEAD = byteArrayOf(0x11, 0x4D, 0x9B.toByte(), 0x74)
        private val ID_INFO = byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66)
        private val ID_TRACKS = byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B)
        private val ID_CLUSTER = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        private val ID_CUES = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)
        private val ID_TAGS = byteArrayOf(0x12, 0x54, 0xC3.toByte(), 0x67)
        private val ID_TIMESTAMP_SCALE = byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte())
        private val ID_DURATION = byteArrayOf(0x44, 0x89.toByte())
        private const val ID_VOID = 0xEC.toByte()

        /** An eight-byte size with every value bit set: what a live mux writes for its Segment. */
        private val UNKNOWN_SIZE = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)
    }

    /**
     * A second, independent Matroska walker, so the assertions do not borrow the parser under test.
     *
     * Deliberately naive: it walks the Segment's top-level children and reads the three values these
     * tests care about. Anything it cannot parse throws, which is itself the assertion.
     */
    private class Reader(
        private val bytes: ByteArray,
    ) {
        fun topLevelIds(): List<Long> = walk().map { it.id }

        fun topLevel(id: Long): Element? = walk().firstOrNull { it.id == id }

        /** The `SeekPosition` of the entry naming `Cues`, or `null` when there is no such entry. */
        fun cuesPosition(seekHead: Element): Long? {
            val seek = children(seekHead).firstOrNull { it.id == ID_SEEK } ?: return null
            val entries = children(seek)
            val target = entries.firstOrNull { it.id == ID_SEEK_ID } ?: return null
            if (unsignedOf(target) != ID_CUES) return null
            return entries.firstOrNull { it.id == ID_SEEK_POSITION }?.let(::unsignedOf)
        }

        /** The `Duration` inside `Info`, or `null` when it is still an unwritten Void. */
        fun duration(): Double? {
            val info = topLevel(ID_INFO) ?: return null
            val duration = children(info).firstOrNull { it.id == ID_DURATION } ?: return null
            return Double.fromBits(unsignedOf(duration))
        }

        private fun walk(): List<Element> {
            val segment = element(element(0L).end)
            check(segment.id == ID_SEGMENT) { "not a Segment at ${segment.start}" }
            val elements = mutableListOf<Element>()
            var at = segment.contentStart
            while (at < bytes.size) {
                val next = element(at)
                elements += next
                at = next.end
            }
            check(at == bytes.size.toLong()) { "the top level does not tile the file: it ended at $at" }
            return elements
        }

        private fun children(parent: Element): List<Element> {
            val elements = mutableListOf<Element>()
            var at = parent.contentStart
            while (at < parent.end) {
                val next = element(at)
                elements += next
                at = next.end
            }
            return elements
        }

        private fun unsignedOf(element: Element): Long {
            var value = 0L
            for (offset in 0 until element.size.toInt()) {
                value = (value shl Byte.SIZE_BITS) or (bytes[(element.contentStart + offset).toInt()].toLong() and 0xFF)
            }
            return value
        }

        private fun element(at: Long): Element {
            val start = at.toInt()
            val idLength = width(bytes[start])
            var id = 0L
            for (offset in 0 until idLength) id = (id shl Byte.SIZE_BITS) or (bytes[start + offset].toLong() and 0xFF)

            val sizeStart = start + idLength
            val sizeLength = width(bytes[sizeStart])
            var size = (bytes[sizeStart].toInt() and (0xFF shr sizeLength)).toLong()
            for (offset in 1 until sizeLength) {
                size = (size shl Byte.SIZE_BITS) or (bytes[sizeStart + offset].toLong() and 0xFF)
            }

            val contentStart = (sizeStart + sizeLength).toLong()
            val allOnes = (1L shl (sizeLength * Byte.SIZE_BITS - sizeLength)) - 1L
            return Element(id, at, contentStart, if (size == allOnes) bytes.size - contentStart else size)
        }

        private fun width(lead: Byte): Int {
            val value = lead.toInt() and 0xFF
            check(value != 0) { "invalid EBML width marker" }
            var length = 1
            var mask = 0x80
            while (value and mask == 0) {
                length++
                mask = mask shr 1
            }
            return length
        }

        data class Element(
            val id: Long,
            val start: Long,
            val contentStart: Long,
            val size: Long,
        ) {
            val end: Long get() = contentStart + size
        }

        companion object {
            const val ID_SEGMENT = 0x18538067L
            const val ID_SEEK_HEAD = 0x114D9B74L
            const val ID_SEEK = 0x4DBBL
            const val ID_SEEK_ID = 0x53ABL
            const val ID_SEEK_POSITION = 0x53ACL
            const val ID_INFO = 0x1549A966L
            const val ID_TRACKS = 0x1654AE6BL
            const val ID_CLUSTER = 0x1F43B675L
            const val ID_CUES = 0x1C53BB6BL
            const val ID_DURATION = 0x4489L
            const val ID_VOID = 0xECL
        }
    }

    private companion object {
        /** 23 minutes: the runtime of the episode the fault was reproduced on. */
        const val RUNTIME_MILLIS = 1_380_000L

        /**
         * Where a real ffmpeg transcode's Segment content starts — 40 bytes of EBML header, then the
         * Segment's 4-byte id and its 8-byte "unknown" size.
         */
        const val REAL_SEGMENT_CONTENT = 52

        /** 4 id + 1 size + 21 of `Seek`: the whole of the written index. */
        const val SEEK_HEAD_BYTES = 26
    }
}
