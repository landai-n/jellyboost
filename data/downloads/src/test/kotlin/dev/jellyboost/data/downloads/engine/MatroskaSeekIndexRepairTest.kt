package dev.jellyboost.data.downloads.engine

import dev.jellyboost.data.downloads.engine.MatroskaSeekIndexRepair.Outcome
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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

        // No runtime, so nothing at all is owed and the file is byte-for-byte as it was. The
        // `SeekHead` it came with is never second-guessed, whatever it points at.
        repair.ensureSeekable(file, runtimeMillis = 0L) shouldBe Outcome.ALREADY_INDEXED

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `a file that already has both a SeekHead and a Duration is not touched`() {
        val fixture = Mkv.transcode(seekHeadInsteadOfVoid = true, existingDurationTicks = 99.0)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.ALREADY_INDEXED

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    // ---- the Duration an already-indexed file may still be missing ---------------------------------

    @Test
    fun `the Duration missing from an already-indexed file is filled in on a later play`() {
        // The MKV-04 shape: a transcode repaired once before its runtime was known keeps its index
        // for ever and used to keep its unwritten Duration for ever with it, so the media
        // notification and PiP never learned how long the file was.
        val fixture = Mkv.transcode(seekHeadInsteadOfVoid = true)
        val file = write(fixture)
        repair.ensureSeekable(file, runtimeMillis = 0L) shouldBe Outcome.ALREADY_INDEXED

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.ALREADY_INDEXED

        val patched = file.readBytes()
        Reader(patched).duration() shouldBe RUNTIME_MILLIS.toDouble()
        // Still ALREADY_INDEXED, and rightly so: the outcome names the seek index, and only the
        // Void reserved for the Duration changed.
        patched.size shouldBe fixture.bytes.size
        unchanged(fixture.bytes, patched, 0, fixture.infoVoid)
        unchanged(fixture.bytes, patched, fixture.infoVoidEnd, fixture.bytes.size.toLong())
    }

    @Test
    fun `a Duration back-fill that fails part-way puts every original byte back`() {
        val fixture = Mkv.transcode(seekHeadInsteadOfVoid = true)
        val file = write(fixture)
        // The one patch of the back-fill dies; the rollback that follows it is write two.
        val writer = FaultyWriter(failAt = setOf(1)) { IOException("the volume went away") }

        repair.ensureSeekable(file, RUNTIME_MILLIS, writer) shouldBe Outcome.FAILED

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `a Duration is never written into an Info the muxer checksummed`() {
        // A `CRC-32` covers every byte of its parent, Voids included: filling one in would leave a
        // file a strict parser is entitled to reject. The seek index goes in regardless — it lives
        // outside `Info`, in the Void the muxer reserved at the top of the Segment.
        val file = write(Mkv.transcode(infoCrc32 = true))

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        Reader(patched).duration().shouldBeNull()
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

    // ---- headers this declines to walk --------------------------------------------------------------

    @Test
    fun `an unknown-size Cluster is refused as a header, not as a foreign file`() {
        // Legal Matroska that a live remux would write, and a header this cannot step through: the
        // element after an unknown-size Cluster cannot be found without parsing the cluster itself.
        // The veto is the right answer; calling it NOT_MATROSKA was not (audit MKV-01).
        val fixture = Mkv.transcode(unknownSizeCluster = true)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.UNSUPPORTED_HEADER

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `a header that stops parsing behind its SeekHead is still an indexed file`() {
        // The walk goes past the `SeekHead` now, to reach the `Info` a back-fill would need — so it
        // can also fail past it. The seek index was settled before that happened, and nothing here
        // is worth writing to the file.
        val fixture = Mkv.transcode(seekHeadInsteadOfVoid = true, unknownSizeCluster = true)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.ALREADY_INDEXED

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `an element id of all ones vetoes the header`() {
        // `0xFF` is the reserved id RFC 8794 gives no element. Stepping over it and writing into the
        // file anyway meant trusting a header that had already said something impossible (MKV-07).
        val fixture = Mkv.transcode(forbiddenIdByte = 0xFF.toByte())
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.UNSUPPORTED_HEADER

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `an element id of all zeroes vetoes the header`() {
        // `0x80` carries no value bits at all: a four-byte id spelled in one, which the format
        // forbids because an id must have exactly one encoding.
        val fixture = Mkv.transcode(forbiddenIdByte = 0x80.toByte())
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.UNSUPPORTED_HEADER

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    // ---- lengths a file we did not write may declare ------------------------------------------------

    @Test
    fun `a Void declaring more than two gigabytes is refused rather than overflowed`() {
        // The adversarial shape MKV-02 names: a Void whose declared length does not fit an `Int`, in
        // a file long enough for it to parse. Every byte count in the patch is derived from that
        // length, so `toInt()` on it used to wrap negative and throw a NegativeArraySizeException out
        // of the coroutine that was opening the file for playback.
        val prefix = Mkv.headerWithVoidDeclaring(HUGE_VOID_BYTES)
        val cues = Mkv.cues()
        val voidEnd = prefix.size + HUGE_VOID_BYTES
        val file = File(directory, "media.mkv")
        RandomAccessFile(file, "rw").use { media ->
            media.write(prefix)
            // Sparse: the 2.5 GiB in the middle is never written, so this costs a few blocks of disk.
            media.setLength(voidEnd + cues.size)
            media.seek(voidEnd)
            media.write(cues)
        }

        // A refusal, not a throw — and NO_ROOM rather than FAILED, because the Void is turned down
        // where it is chosen rather than where it would overflow.
        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.NO_ROOM

        RandomAccessFile(file, "r").use { media ->
            val header = ByteArray(prefix.size)
            media.readFully(header)
            header.contentEquals(prefix) shouldBe true
        }
    }

    @Test
    fun `a film past two gigabytes gets a sixty-four bit index`() {
        // Every offset in the repair is a `Long`, and this is the one that has to survive the round
        // trip into the file: a 2.5 GiB transcode's `Cues` sit past `Int.MAX_VALUE`, and a
        // `SeekPosition` that wrapped there would point Media3 at the wrong end of the film.
        // Sparse: only the header and the trailer are ever written, so this costs a few blocks.
        val fixture = Mkv.sparse(clusterBytes = HUGE_CLUSTER_BYTES)
        val file = sparseFile(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val written = read(file, fixture.segmentContent, SEEK_HEAD_BYTES)
        val expected = fixture.cuesOffset - fixture.segmentContent
        (expected > Int.MAX_VALUE.toLong()) shouldBe true
        written.toHex() shouldBe "114d9b74954dbb9253ab841c53bb6b53ac88" + "%016x".format(expected)
        // The file did not grow by a byte, so every offset the Cues already carry is still right.
        file.length() shouldBe fixture.length
    }

    // ---- how far back the Cues may be ---------------------------------------------------------------

    @Test
    fun `Cues further from the end than one window are still found`() {
        // Two megabytes of `Tags` behind the index — a chapter list, an attachment — used to push
        // the `Cues` out of the single window the search read, and a perfectly good file was
        // reported as having no index at all (audit MKV-05).
        val fixture = Mkv.transcode(tagsAfterCues = true, tagsBytes = 2 * 1024 * 1024)
        val file = write(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        val patched = file.readBytes()
        val seekHead = Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        Reader(patched).cuesPosition(seekHead) shouldBe fixture.cuesOffset - fixture.segmentContent
    }

    @Test
    fun `Cues further back than the search will reach are given up on rather than searched for`() {
        // The bound on the walk back. Past it this is not looking for a trailer any more, and the
        // answer that costs nothing — "no index here" — is the one to give.
        val fixture = Mkv.sparseTrailer(tagsBytes = 32L * 1024L * 1024L)
        val file = sparseFile(fixture)

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.NO_CUES

        read(file, 0L, fixture.prefix.size).contentEquals(fixture.prefix) shouldBe true
    }

    // ---- a write that dies part-way -----------------------------------------------------------------

    @Test
    fun `a write that fails part-way puts every original byte back`() {
        val fixture = Mkv.transcode()
        val file = write(fixture)
        // The second write is the SeekHead: by then the Duration is already on the platter, so this
        // is the file caught exactly half-patched.
        val writer = FaultyWriter(failAt = setOf(2)) { IOException("the volume went away") }

        repair.ensureSeekable(file, RUNTIME_MILLIS, writer) shouldBe Outcome.FAILED

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `a failure that is not an IOException is rolled back just the same`() {
        val fixture = Mkv.transcode()
        val file = write(fixture)
        val writer = FaultyWriter(failAt = setOf(2)) { IllegalStateException("the channel was closed") }

        // Rollback keys on the write having failed, not on how — the old code only rolled back a
        // *verify* failure and let everything else past, taking the half-patched file with it.
        repair.ensureSeekable(file, RUNTIME_MILLIS, writer) shouldBe Outcome.FAILED

        file.readBytes().contentEquals(fixture.bytes) shouldBe true
    }

    @Test
    fun `the Duration is patched before the SeekHead`() {
        val fixture = Mkv.transcode()
        val file = write(fixture)
        val writer = FaultyWriter(failAt = emptySet()) { IOException() }

        repair.ensureSeekable(file, RUNTIME_MILLIS, writer) shouldBe Outcome.INDEXED

        // Each patch alone leaves a file that parses, and the SeekHead is the marker that says
        // "already repaired" — so it has to be last, or a torn run is never retried.
        writer.offsets shouldBe listOf(fixture.infoVoid, fixture.segmentContent)
    }

    @Test
    fun `a run torn after the Duration leaves a file the next play still repairs`() {
        val fixture = Mkv.transcode()
        val file = write(fixture)
        // The SeekHead write fails and so does the first write of the rollback: the power-loss shape,
        // where nothing gets to put anything back.
        val writer = FaultyWriter(failAt = setOf(2, 3)) { IOException("the volume went away") }

        repair.ensureSeekable(file, RUNTIME_MILLIS, writer) shouldBe Outcome.FAILED

        // What is left is the Duration alone: a file that still parses, and still has no SeekHead —
        // which is what makes the next play repair it instead of short-circuiting on the marker.
        val torn = Reader(file.readBytes())
        torn.duration() shouldBe RUNTIME_MILLIS.toDouble()
        torn.topLevel(Reader.ID_SEEK_HEAD).shouldBeNull()

        repair.ensureSeekable(file, RUNTIME_MILLIS) shouldBe Outcome.INDEXED
        Reader(file.readBytes()).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
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

    // ---- a whole file ffmpeg actually wrote ---------------------------------------------------------

    @Test
    fun `a real Matroska file that already carries its index is left exactly as it is`() {
        val file = File(directory, "media.mkv").apply { writeBytes(realMkv()) }

        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.ALREADY_INDEXED

        // Nothing is owed: the `SeekHead` is ffmpeg's own, the `Duration` is ffmpeg's own, and the
        // `Info` carries a real `CRC-32` that a back-fill would have invalidated.
        file.readBytes().contentEquals(realMkv()) shouldBe true
        Reader(realMkv()).duration() shouldBe REAL_DURATION
    }

    @Test
    fun `a real ffmpeg file without its SeekHead is indexed at the offset ffmpeg itself recorded`() {
        val real = realMkv()
        // ffmpeg's own answer to the question this class exists to answer, read out of the file it
        // wrote: the `SeekPosition` of its `Cues` entry. Nothing here computes it — it is the oracle.
        val ffmpegsAnswer =
            Reader(real).cuesPosition(Reader(real).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()).shouldNotBeNull()
        val file = File(directory, "media.mkv").apply { writeBytes(liveMux(real)) }

        // 1. Scan — the download engine's own reading of the bytes as they would have arrived.
        val scanner = MkvClusterScanner()
        val stream = file.readBytes()
        var at = 0
        while (at < stream.size) {
            val length = minOf(SCAN_CHUNK_BYTES, stream.size - at)
            scanner.consume(stream, at, length)
            at += length
        }
        scanner.mediaMillisReceived shouldBe REAL_LAST_CLUSTER_MILLIS

        // 2. Repair.
        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.INDEXED

        // 3. Verify — the whole file still parses, and the twenty-six bytes written are the ones
        //    ffmpeg would have written itself.
        val patched = file.readBytes()
        patched.size shouldBe real.size
        val seekHead = Reader(patched).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        seekHead.start shouldBe REAL_SEGMENT_CONTENT.toLong()
        Reader(patched).cuesPosition(seekHead) shouldBe ffmpegsAnswer
        patched.copyOfRange(REAL_SEGMENT_CONTENT, REAL_SEGMENT_CONTENT + SEEK_HEAD_BYTES).toHex() shouldBe
            "114d9b74954dbb9253ab841c53bb6b53ac88" + "%016x".format(ffmpegsAnswer)
        // ffmpeg's own `Duration` is still there and was not rewritten; its `Info` is checksummed.
        Reader(patched).duration() shouldBe REAL_DURATION

        // 4. And again — the playback path runs this on every open.
        repair.ensureSeekable(file, runtimeMillis = RUNTIME_MILLIS) shouldBe Outcome.ALREADY_INDEXED
        file.readBytes().contentEquals(patched) shouldBe true
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * Thirteen kilobytes of unmodified `ffmpeg 7.1.1` output: six seconds of H.264 in Matroska, six
     * clusters, and a `Cues` index ffmpeg built from the frames it actually encoded — the one thing
     * no synthetic fixture in this file can claim. It is committed rather than generated because a
     * unit test may not depend on an ffmpeg being on the machine.
     */
    private fun realMkv(): ByteArray = checkNotNull(javaClass.getResourceAsStream("/ffmpeg-matroska.mkv")).readBytes()

    /**
     * [real] with its `SeekHead` replaced by a Void of exactly the same length — which is the shape
     * jellyfin-ffmpeg lands on the device, and the shape `ffmpeg-transcode-header.bin` was cut from.
     *
     * A Void where a `SeekHead` was is ordinary Matroska: `ffprobe` reads the result, and `ffmpeg`
     * decodes all six seconds of it, both before and after the repair — checked when the fixture was
     * made. Deriving it here rather than committing it keeps the committed bytes ffmpeg's own, which
     * is what lets the test above use ffmpeg's `SeekPosition` as an independent answer.
     */
    private fun liveMux(real: ByteArray): ByteArray {
        val seekHead = Reader(real).topLevel(Reader.ID_SEEK_HEAD).shouldNotBeNull()
        val total = (seekHead.end - seekHead.start).toInt()
        return real.copyOfRange(0, seekHead.start.toInt()) +
            Mkv.void(total) +
            real.copyOfRange(seekHead.end.toInt(), real.size)
    }

    /** Writes a [Mkv.Sparse] fixture: the header, the trailer, and a hole between them. */
    private fun sparseFile(fixture: Mkv.Sparse): File {
        val file = File(directory, "media.mkv")
        RandomAccessFile(file, "rw").use { media ->
            media.write(fixture.prefix)
            media.setLength(fixture.length)
            media.seek(fixture.cuesOffset)
            media.write(fixture.cues)
        }
        return file
    }

    private fun read(
        file: File,
        at: Long,
        count: Int,
    ): ByteArray =
        RandomAccessFile(file, "r").use { media ->
            val bytes = ByteArray(count)
            media.seek(at)
            media.readFully(bytes)
            bytes
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun write(fixture: Mkv.Fixture): File = File(directory, "media.mkv").apply { writeBytes(fixture.bytes) }

    /**
     * The production writer with a fault at chosen writes, which is the only way to reach the
     * rollback: it needs an I/O error between two writes to the one file the repair holds open.
     *
     * Writes are counted from one across the whole run, rollback included, so `failAt = {2, 3}` is
     * "the second patch died, and so did the attempt to undo the first" — the shape a power cut has.
     */
    private class FaultyWriter(
        private val failAt: Set<Int>,
        private val error: () -> Exception,
    ) : MatroskaSeekIndexRepair.PatchWriter {
        /** Every offset written to, in order — which is what pins the patch order. */
        val offsets = mutableListOf<Long>()
        private var writes = 0

        override fun write(
            media: RandomAccessFile,
            at: Long,
            bytes: ByteArray,
        ) {
            writes++
            offsets += at
            if (writes in failAt) throw error()
            media.seek(at)
            media.write(bytes)
        }
    }

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
            infoCrc32: Boolean = false,
            cues: Boolean = true,
            tagsAfterCues: Boolean = false,
            tagsBytes: Int = 64,
            seekHeadInsteadOfVoid: Boolean = false,
            decoyCuesInCluster: Boolean = false,
            unknownSizeCluster: Boolean = false,
            forbiddenIdByte: Byte? = null,
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
                (if (infoCrc32) crc32() else ByteArray(0)) +
                    void(infoVoidBytes) +
                    element(ID_TIMESTAMP_SCALE, unsigned(timestampScaleNanos)) +
                    (existingDurationTicks?.let { element(ID_DURATION, bigEndian(it.toRawBits())) } ?: ByteArray(0))

            val firstCluster =
                if (unknownSizeCluster) {
                    // The size a live remux writes when it does not yet know how long its cluster
                    // will be. Legal Matroska, and a header this cannot step through.
                    ID_CLUSTER + UNKNOWN_SIZE + clusterPayload(decoyCuesInCluster)
                } else {
                    element(ID_CLUSTER, clusterPayload(decoyCuesInCluster))
                }

            val prefix =
                header + reserved + element(ID_INFO, infoBody) +
                    element(ID_TRACKS, ByteArray(64) { 0x11 }) +
                    (forbiddenIdByte?.let { byteArrayOf(it) + wideLength(4L) + ByteArray(4) } ?: ByteArray(0)) +
                    firstCluster +
                    element(ID_CLUSTER, ByteArray(4_096) { (it % 97).toByte() })

            val tail =
                when {
                    !cues -> ByteArray(0)
                    tagsAfterCues -> cues() + element(ID_TAGS, ByteArray(tagsBytes) { 0x44 })
                    else -> cues()
                }

            val reservedVoidEnd = segmentContent + reserved.size
            val infoVoid = reservedVoidEnd + HEADER_BYTES + (if (infoCrc32) CRC32_BYTES else 0)
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

        /** The four-byte checksum a muxer writes over everything its parent contains. */
        fun crc32(): ByteArray = byteArrayOf(ID_CRC32, 0x84.toByte()) + ByteArray(CRC32_BYTES)

        /**
         * A transcode whose one cluster *declares* [clusterBytes] of frame data without a byte of it
         * being written, so a film past `Int.MAX_VALUE` costs a few blocks of disk to build.
         *
         * The caller writes [prefix] at zero, sets the length to [length], and puts [cues] at
         * [cuesOffset]; everything between is a hole.
         */
        fun sparse(clusterBytes: Long): Sparse {
            val header = element(ID_EBML, ByteArray(35) { 0x22 }) + ID_SEGMENT + UNKNOWN_SIZE
            val infoBody = void(INFO_VOID_BYTES) + element(ID_TIMESTAMP_SCALE, unsigned(1_000_000L))
            val prefix =
                header + void(RESERVED_VOID_BYTES) + element(ID_INFO, infoBody) +
                    element(ID_TRACKS, ByteArray(64) { 0x11 }) +
                    ID_CLUSTER + wideLength(clusterBytes)
            val cues = cues()
            val cuesOffset = prefix.size + clusterBytes
            return Sparse(
                prefix = prefix,
                cues = cues,
                segmentContent = header.size.toLong(),
                cuesOffset = cuesOffset,
                length = cuesOffset + cues.size,
            )
        }

        /**
         * A trailer that *declares* [tagsBytes] of `Tags` behind the `Cues` without writing them,
         * which is how a file whose index sits tens of megabytes from the end is built cheaply.
         */
        fun sparseTrailer(tagsBytes: Long): Sparse {
            val header = element(ID_EBML, ByteArray(35) { 0x22 }) + ID_SEGMENT + UNKNOWN_SIZE
            val infoBody = void(INFO_VOID_BYTES) + element(ID_TIMESTAMP_SCALE, unsigned(1_000_000L))
            val prefix =
                header + void(RESERVED_VOID_BYTES) + element(ID_INFO, infoBody) +
                    element(ID_TRACKS, ByteArray(64) { 0x11 }) +
                    element(ID_CLUSTER, ByteArray(4_096) { (it % 97).toByte() })
            val cues = cues() + ID_TAGS + wideLength(tagsBytes)
            return Sparse(
                prefix = prefix,
                cues = cues,
                segmentContent = header.size.toLong(),
                cuesOffset = prefix.size.toLong(),
                length = prefix.size + cues.size + tagsBytes,
            )
        }

        /** A file too big to hold in memory: what to write, where, and how long it ends up. */
        class Sparse(
            val prefix: ByteArray,
            val cues: ByteArray,
            val segmentContent: Long,
            val cuesOffset: Long,
            val length: Long,
        )

        /**
         * An EBML header and an unknown-size Segment, then a Void that *declares* [content] bytes of
         * padding without a byte of it being written — the file is left sparse behind it.
         */
        fun headerWithVoidDeclaring(content: Long): ByteArray =
            element(ID_EBML, ByteArray(35) { 0x22 }) + ID_SEGMENT + UNKNOWN_SIZE +
                byteArrayOf(ID_VOID) + wideLength(content)

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

        fun void(total: Int): ByteArray =
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
        fun wideLength(value: Long): ByteArray = bigEndian(value).also { it[0] = 0x01 }

        private fun bigEndian(value: Long): ByteArray =
            ByteArray(Long.SIZE_BYTES) { (value ushr ((Long.SIZE_BYTES - 1 - it) * Byte.SIZE_BITS)).toByte() }

        /** 4 bytes of id plus the 8-byte length form: the header every fixture element carries. */
        private const val HEADER_BYTES = 12

        private const val VOID_LONG_FORM_MIN = 10
        private const val DECOY_OFFSET = 700

        /** A CRC-32 is four bytes, and the spec allows it to be spelled no other way. */
        const val CRC32_BYTES = 4

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
        private const val ID_CRC32 = 0xBF.toByte()

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

        /**
         * The `SeekPosition` of the entry naming `Cues`, or `null` when there is no such entry.
         *
         * Every `Seek` child is searched, not just the first: what this code writes has one entry,
         * but what ffmpeg writes has four and opens with a `CRC-32`.
         */
        fun cuesPosition(seekHead: Element): Long? =
            children(seekHead)
                .filter { it.id == ID_SEEK }
                .map(::children)
                .firstOrNull { entry -> entry.any { it.id == ID_SEEK_ID && unsignedOf(it) == ID_CUES } }
                ?.firstOrNull { it.id == ID_SEEK_POSITION }
                ?.let(::unsignedOf)

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

        /** 2.5 GiB: past `Int.MAX_VALUE`, which is the whole point of it. */
        const val HUGE_VOID_BYTES = 2_684_354_560L

        /** 2.5 GiB of declared frame data, so the `Cues` behind it land past `Int.MAX_VALUE` too. */
        const val HUGE_CLUSTER_BYTES = 2_684_354_560L

        /** The `Duration` ffmpeg wrote into the real fixture: six seconds, at a tick per ms. */
        const val REAL_DURATION = 6_000.0

        /** The timestamp of the last of that file's six clusters, as its own bytes give it. */
        const val REAL_LAST_CLUSTER_MILLIS = 5_533L

        /** Small enough that the real file crosses several chunk boundaries on the way in. */
        const val SCAN_CHUNK_BYTES = 4_096
    }
}
