package dev.jellyboost.data.downloads.engine

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes a transcoded download seekable by writing the one index entry its muxer could not.
 *
 * A transcoded download is Matroska muxed live into a chunked HTTP response, and ffmpeg cannot seek
 * backwards in a pipe: the `Cues` are written (it holds them in memory and appends them in its
 * trailer) but the `SeekHead` that would point at them, and the `Duration`, stay the Voids reserved
 * for them —
 *
 * ```
 * 1A45DFA3  EBML header
 * 18538067  Segment, size = 01 FF FF FF FF FF FF FF   ← "unknown", a live mux
 *   EC      Void, 152 bytes                            ← reserved for the SeekHead, never written
 *   1549A966  Info
 *     EC    Void, 11 bytes                             ← reserved for Duration, never written
 *     2AD7B1  TimestampScale
 *   1654AE6B  Tracks
 *   1F43B675  Cluster …
 *   1C53BB6B  Cues                                     ← written, at the very end of the file
 * ```
 *
 * Media3's `MatroskaExtractor` learns where `Cues` live only from a `SeekHead`; without one it
 * publishes `SeekMap.Unseekable` and every drag of the seek bar restarts the file. An `ORIGINAL`
 * download carries a real `SeekHead` at the same offset and is unaffected.
 *
 * Both patches land **inside Void elements**, so no meaningful byte is overwritten, the file's length
 * never changes, and every offset the `Cues` already hold stays valid — growing the header instead
 * would move every cluster and invalidate all of them.
 *
 * Every step is a veto that leaves the file byte-for-byte as it was, and a `SeekHead` already present
 * is never second-guessed (though a missing `Duration` is still filled in). After writing, the
 * patched regions are read back and the header re-walked; a disagreement restores the original bytes,
 * because a download that seeks badly is a bug and one that no longer parses is a lost gigabyte.
 */
@Singleton
@Suppress(
    "ReturnCount",
)
internal class MatroskaSeekIndexRepair
    @Inject
    constructor() {
        enum class Outcome {
            INDEXED,

            /** A `SeekHead` was already there; a missing `Duration` is still filled in on this path. */
            ALREADY_INDEXED,

            /** Not a Matroska file: no `EBML` header, or no `Segment` behind it. */
            NOT_MATROSKA,

            /**
             * Matroska with a header this cannot step through — an unknown-size element other than
             * the `Segment`, an id the spec forbids, or a length running past the end of the file.
             */
            UNSUPPORTED_HEADER,

            /** No `Cues` element at the end of the file: there is no index to point at. */
            NO_CUES,

            /** No reserved Void large enough to hold a `SeekHead` without moving anything. */
            NO_ROOM,

            /** The file could not be read or written; or the write was rolled back. */
            FAILED,
        }

        /**
         * Ensures [file] carries a `SeekHead`, writing one into its reserved Void when it does not.
         * Blocking I/O. A [runtimeMillis] of zero or less simply skips the `Duration` half.
         */
        fun ensureSeekable(
            file: File,
            runtimeMillis: Long,
        ): Outcome = ensureSeekable(file, runtimeMillis, PatchWriter.Direct)

        /** [ensureSeekable] with the seam a fault-injection test writes through — see [PatchWriter]. */
        @Suppress("TooGenericExceptionCaught")
        internal fun ensureSeekable(
            file: File,
            runtimeMillis: Long,
            writer: PatchWriter,
        ): Outcome {
            val outcome =
                try {
                    RandomAccessFile(file, "rw").use { media -> index(media, runtimeMillis, writer) }
                } catch (error: IOException) {
                    Timber.w(error, "Could not index %s for seeking", file.name)
                    Outcome.FAILED
                } catch (error: RuntimeException) {
                    // This runs inside the coroutine opening a file for playback: a header we cannot
                    // make sense of is one we decline to repair, never an exception thrown at the player.
                    Timber.e(error, "Could not index %s for seeking", file.name)
                    Outcome.FAILED
                }
            when (outcome) {
                Outcome.INDEXED -> Timber.i("Indexed %s for seeking", file.name)
                Outcome.NO_CUES, Outcome.NO_ROOM, Outcome.UNSUPPORTED_HEADER ->
                    Timber.w("%s cannot be indexed for seeking (%s)", file.name, outcome)
                else -> Unit
            }
            return outcome
        }

        private fun index(
            media: RandomAccessFile,
            runtimeMillis: Long,
            writer: PatchWriter,
        ): Outcome {
            val length = media.length()
            val segment = segment(media, length) ?: return Outcome.NOT_MATROSKA
            val header = walkHeader(media, length, segment.contentStart) ?: return Outcome.UNSUPPORTED_HEADER
            if (header.seekHead != null) {
                return backfill(media, length, segment.contentStart, header, runtimeMillis, writer)
            }
            val reserved = header.void ?: return Outcome.NO_ROOM
            val cues = findCues(media, length) ?: return Outcome.NO_CUES

            // The order is the crash-safety property: `Duration` first, `SeekHead` last, because each
            // patch alone still parses and the `SeekHead` is the "already repaired" marker. A run torn
            // between the two leaves a file that is still playable and still re-repairable.
            val patches =
                buildList {
                    duration(media, header.info, runtimeMillis)?.let(::add)
                    add(Patch(reserved.start, seekHead(reserved.length.toInt(), cues - segment.contentStart)))
                }
            return if (write(media, length, segment.contentStart, patches, writer)) Outcome.INDEXED else Outcome.FAILED
        }

        /**
         * The `Duration` an already-indexed file may still be missing — a transcode first opened
         * before its runtime was known would otherwise stay without one for the life of the file.
         * The outcome stays [Outcome.ALREADY_INDEXED]: that names the seek index, which this did not
         * touch.
         */
        @Suppress("LongParameterList")
        private fun backfill(
            media: RandomAccessFile,
            length: Long,
            segmentContent: Long,
            header: Header,
            runtimeMillis: Long,
            writer: PatchWriter,
        ): Outcome {
            val duration = duration(media, header.info, runtimeMillis) ?: return Outcome.ALREADY_INDEXED
            Timber.i("Back-filling the Duration of an already-indexed file")
            return if (write(media, length, segmentContent, listOf(duration), writer)) {
                Outcome.ALREADY_INDEXED
            } else {
                Outcome.FAILED
            }
        }

        // ---- writing ------------------------------------------------------------------------------

        /**
         * Applies [patches], then proves the result: the read-back catches a short write, the second
         * header walk catches a patch that is well-formed but does not chain, and **anything thrown**
         * puts the original bytes back. Process death between two writes is what [index]'s order covers.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun write(
            media: RandomAccessFile,
            length: Long,
            segmentContent: Long,
            patches: List<Patch>,
            writer: PatchWriter,
        ): Boolean {
            val originals = patches.map { patch -> Patch(patch.start, read(media, patch.start, patch.bytes.size)) }
            val indexed =
                try {
                    patches.forEach { patch -> writer.write(media, patch.start, patch.bytes) }
                    media.fd.sync()
                    patches.all { patch -> read(media, patch.start, patch.bytes.size).contentEquals(patch.bytes) } &&
                        walkHeader(media, length, segmentContent)?.seekHead != null
                } catch (error: Exception) {
                    Timber.e(error, "Seek index write failed part-way")
                    false
                }
            if (indexed) return true

            Timber.e("Seek index did not land; restoring the original header")
            try {
                originals.forEach { patch -> writer.write(media, patch.start, patch.bytes) }
                media.fd.sync()
            } catch (error: Exception) {
                // The bytes are known and the file will not take them; saying so loudly is the remedy.
                Timber.e(error, "Could not restore the original header; the file may no longer parse")
            }
            return false
        }

        /**
         * A `SeekHead` naming the `Cues`, padded with a Void to exactly [total] bytes. One entry is all
         * Media3 needs; [cuesPosition] is relative to the Segment's *content*, which is what
         * `SeekPosition` means and what Media3 adds its own `segmentContentPosition` back onto.
         */
        private fun seekHead(
            total: Int,
            cuesPosition: Long,
        ): ByteArray {
            val entry =
                ID_SEEK_ID + length(ID_CUES.size) + ID_CUES +
                    ID_SEEK_POSITION + length(Long.SIZE_BYTES) + bigEndian(cuesPosition)
            val seek = ID_SEEK + length(entry.size) + entry
            val seekHead = ID_SEEK_HEAD + length(seek.size) + seek
            return seekHead + void(total - seekHead.size)
        }

        /**
         * The `Duration` ffmpeg reserved room for and never came back to write, or `null` when there
         * is nothing to write or nowhere to put it. A 64-bit float because that is exactly the 11
         * bytes ffmpeg's `put_ebml_void(11)` reserves.
         *
         * An `Info` carrying a **written** `CRC-32` is left alone: the checksum covers the Void this
         * would write into, so filling it in would leave a file a strict parser may reject.
         */
        private fun duration(
            media: RandomAccessFile,
            info: Element?,
            runtimeMillis: Long,
        ): Patch? {
            if (info == null || runtimeMillis <= 0L) return null
            val children = children(media, info) ?: return null
            if (children.any { it.id == ID_DURATION_VALUE || it.id == ID_CRC32_VALUE }) return null
            val reserved = children.firstOrNull { it.id == ID_VOID_VALUE && fits(it.length, DURATION_BYTES) }
            if (reserved == null) return null

            val ticks = runtimeMillis * NANOS_PER_MILLI.toDouble() / timestampScale(media, children)
            val duration = ID_DURATION + length(Long.SIZE_BYTES) + bigEndian(ticks.toRawBits())
            return Patch(reserved.start, duration + void(reserved.length.toInt() - duration.size))
        }

        /**
         * A Void of exactly [total] bytes, in the two forms ffmpeg's `put_ebml_void` writes: a
         * one-byte length below ten bytes, an eight-byte one at or above it.
         */
        private fun void(total: Int): ByteArray =
            when {
                total == 0 -> ByteArray(0)
                total < VOID_LONG_FORM_MIN ->
                    byteArrayOf(ID_VOID) + length(total - VOID_SHORT_HEADER) + ByteArray(total - VOID_SHORT_HEADER)

                else ->
                    byteArrayOf(ID_VOID) + wideLength(total - VOID_LONG_HEADER) + ByteArray(total - VOID_LONG_HEADER)
            }

        // ---- reading ------------------------------------------------------------------------------

        /** The Segment, whose content offset every `SeekPosition` and `CueClusterPosition` counts from. */
        private fun segment(
            media: RandomAccessFile,
            length: Long,
        ): Element? {
            val ebml = element(media, 0L, length) ?: return null
            if (ebml.id != ID_EBML_VALUE) return null
            // The one element allowed an unknown size here: a live mux cannot know how long it will be.
            val segment = element(media, ebml.end, length, unknownSizeRunsToEnd = true) ?: return null
            return segment.takeIf { it.id == ID_SEGMENT_VALUE }
        }

        /**
         * Walks the Segment's children up to the first cluster. A `SeekHead` does **not** end the
         * walk: `Info` sits behind it, and an already-indexed file may still be owed a `Duration`.
         *
         * @return `null` when the header does not parse and no `SeekHead` was reached — a file this
         *   has no business writing to.
         */
        private fun walkHeader(
            media: RandomAccessFile,
            length: Long,
            from: Long,
        ): Header? {
            var at = from
            var void: Element? = null
            var info: Element? = null
            var seekHead: Element? = null
            var seen = 0
            while (at < length && seen++ < MAX_HEADER_ELEMENTS) {
                val element =
                    element(media, at, length)
                        ?: return seekHead?.let { Header(seekHead = it, void = null, info = null) }
                when (element.id) {
                    ID_SEEK_HEAD_VALUE -> if (seekHead == null) seekHead = element
                    ID_CLUSTER_VALUE -> return Header(seekHead = seekHead, void = void, info = info)
                    ID_VOID_VALUE -> if (void == null && fits(element.length, SEEK_HEAD_BYTES)) void = element
                    ID_INFO_VALUE -> if (info == null) info = element
                }
                at = element.end
            }
            return Header(seekHead = seekHead, void = void, info = info)
        }

        /**
         * The offset of the `Cues`, found by scanning the tail of the file for its id. A candidate is
         * believed only when the elements starting at it land **exactly** on the end of the file,
         * which makes a stray `1C 53 BB 6B` inside frame data essentially impossible to accept.
         *
         * Scanned backwards in [CUES_SCAN_BYTES] windows rather than one: `Tags`, `Chapters` or an
         * attachment written behind the index push it arbitrarily far back. Windows overlap by an id
         * width so a split `Cues` id is still seen, and the search is bounded by [MAX_CUES_SCAN_BYTES].
         */
        private fun findCues(
            media: RandomAccessFile,
            length: Long,
        ): Long? {
            var end = length
            var scanned = 0L
            while (end > 0L && scanned < MAX_CUES_SCAN_BYTES) {
                val from = (end - CUES_SCAN_BYTES).coerceAtLeast(0L)
                val window = read(media, from, (end - from).toInt())
                for (offset in 0..window.size - ID_CUES.size) {
                    if (!matches(window, offset, ID_CUES)) continue
                    val at = from + offset
                    if (endsFile(media, at, length)) return at
                }
                if (from == 0L) return null
                scanned += window.size
                end = from + ID_CUES.size - 1
            }
            return null
        }

        private fun endsFile(
            media: RandomAccessFile,
            at: Long,
            length: Long,
        ): Boolean {
            var position = at
            var seen = 0
            while (position < length && seen++ < MAX_TRAILING_ELEMENTS) {
                position = element(media, position, length)?.end ?: return false
            }
            return position == length
        }

        /** The children of [parent], or `null` when any of them does not parse. */
        private fun children(
            media: RandomAccessFile,
            parent: Element,
        ): List<Element>? {
            val children = mutableListOf<Element>()
            var at = parent.contentStart
            while (at < parent.end) {
                val child = element(media, at, parent.end) ?: return null
                children += child
                at = child.end
            }
            return children
        }

        /** Nanoseconds per timestamp tick, or Matroska's default when it is absent or implausible. */
        private fun timestampScale(
            media: RandomAccessFile,
            children: List<Element>,
        ): Long =
            children
                .firstOrNull { it.id == ID_TIMESTAMP_SCALE_VALUE }
                ?.let { unsigned(media, it) }
                ?.takeIf { it in MIN_TIMESTAMP_SCALE..MAX_TIMESTAMP_SCALE }
                ?: DEFAULT_TIMESTAMP_SCALE

        private fun unsigned(
            media: RandomAccessFile,
            element: Element,
        ): Long? {
            if (element.size !in 1..Long.SIZE_BYTES.toLong()) return null
            var value = 0L
            for (byte in read(media, element.contentStart, element.size.toInt())) {
                value = (value shl Byte.SIZE_BITS) or (byte.toLong() and BYTE_MASK)
            }
            return value.takeIf { it >= 0L }
        }

        /**
         * One element header at [at], bounded by [limit]. [unknownSizeRunsToEnd] is true only for the
         * Segment: anything else with an unknown size cannot be stepped over, and a header this cannot
         * step through is a header it must not write into.
         */
        private fun element(
            media: RandomAccessFile,
            at: Long,
            limit: Long,
            unknownSizeRunsToEnd: Boolean = false,
        ): Element? {
            if (at < 0L || at + MIN_HEADER_BYTES > limit) return null
            val available = minOf(MAX_HEADER_BYTES.toLong(), limit - at).toInt()
            val header = read(media, at, available)

            val id = identifier(header, available) ?: return null
            val size = varInt(header, id.length, available) ?: return null
            val contentStart = at + id.length + size.length
            val content =
                when {
                    !size.unknown && size.value >= 0L && contentStart + size.value <= limit -> size.value
                    size.unknown && unknownSizeRunsToEnd -> limit - contentStart
                    else -> return null
                }
            return Element(id = id.value, start = at, contentStart = contentStart, size = content)
        }

        /**
         * The element id at the head of [header]. The two encodings RFC 8794 §5 forbids — value bits
         * all zero (a longer spelling of a shorter id) and value bits all one (the reserved id) — are
         * refused rather than stepped over, which vetoes the whole header.
         */
        private fun identifier(
            header: ByteArray,
            available: Int,
        ): VarInt? {
            val lead = header[0].toInt() and BYTE_MASK.toInt()
            if (lead == 0) return null
            var length = 1
            var mask = HIGH_BIT
            while (lead and mask == 0) {
                length++
                mask = mask shr 1
            }
            if (length > MAX_ID_BYTES || length > available) return null
            var value = 0L
            for (index in 0 until length) value = (value shl Byte.SIZE_BITS) or (header[index].toLong() and BYTE_MASK)
            val payload = value and valueBits(length)
            if (payload == 0L || payload == valueBits(length)) return null
            return VarInt(value = value, length = length, unknown = false)
        }

        /** One EBML variable-width integer: leading zero bits give the width, the rest the value. */
        private fun varInt(
            buffer: ByteArray,
            at: Int,
            available: Int,
        ): VarInt? {
            if (at >= available) return null
            val lead = buffer[at].toInt() and BYTE_MASK.toInt()
            if (lead == 0) return null

            var length = 1
            var mask = HIGH_BIT
            while (lead and mask == 0) {
                length++
                mask = mask shr 1
            }
            if (at + length > available) return null

            var value = (lead and (mask - 1)).toLong()
            for (index in 1 until length) {
                value = (value shl Byte.SIZE_BITS) or (buffer[at + index].toLong() and BYTE_MASK)
            }
            return VarInt(value = value, length = length, unknown = value == valueBits(length))
        }

        private fun read(
            media: RandomAccessFile,
            at: Long,
            count: Int,
        ): ByteArray {
            val bytes = ByteArray(count)
            media.seek(at)
            media.readFully(bytes)
            return bytes
        }

        private data class Element(
            val id: Long,
            val start: Long,
            val contentStart: Long,
            val size: Long,
        ) {
            val end: Long get() = contentStart + size

            /** Id, size and content together — what a Void has to spare. */
            val length: Long get() = contentStart - start + size
        }

        private data class VarInt(
            val value: Long,
            val length: Int,
            val unknown: Boolean,
        )

        private class Header(
            val seekHead: Element?,
            val void: Element?,
            val info: Element?,
        )

        private class Patch(
            val start: Long,
            val bytes: ByteArray,
        )

        /**
         * The one way bytes reach the file, so a test can fail a write between two patches. The
         * rollback path is otherwise unreachable, and it is what decides whether a failed repair costs
         * the user a download.
         */
        internal fun interface PatchWriter {
            fun write(
                media: RandomAccessFile,
                at: Long,
                bytes: ByteArray,
            )

            companion object {
                val Direct =
                    PatchWriter { media, at, bytes ->
                        media.seek(at)
                        media.write(bytes)
                    }
            }
        }

        private companion object {
            const val ID_EBML_VALUE = 0x1A45DFA3L

            const val ID_SEGMENT_VALUE = 0x18538067L

            const val ID_SEEK_HEAD_VALUE = 0x114D9B74L
            val ID_SEEK_HEAD = byteArrayOf(0x11, 0x4D, 0x9B.toByte(), 0x74)

            val ID_SEEK = byteArrayOf(0x4D, 0xBB.toByte())

            val ID_SEEK_ID = byteArrayOf(0x53, 0xAB.toByte())

            val ID_SEEK_POSITION = byteArrayOf(0x53, 0xAC.toByte())

            val ID_CUES = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)

            const val ID_INFO_VALUE = 0x1549A966L

            /** `Duration`, in `TimestampScale` ticks, as a float. */
            const val ID_DURATION_VALUE = 0x4489L
            val ID_DURATION = byteArrayOf(0x44, 0x89.toByte())

            /** `TimestampScale`, nanoseconds per tick. */
            const val ID_TIMESTAMP_SCALE_VALUE = 0x2AD7B1L

            /** `Cluster`: the point in the header walk past which nothing more can be decided. */
            const val ID_CLUSTER_VALUE = 0x1F43B675L

            /** `CRC-32`, whose checksum covers every byte of its parent's content — Voids included. */
            const val ID_CRC32_VALUE = 0xBFL

            /** `Void` — the padding a muxer leaves behind, and the only bytes this ever overwrites. */
            const val ID_VOID_VALUE = 0xECL
            const val ID_VOID = 0xEC.toByte()

            /** 4 id + 1 size + (2 + 1 + 4) `SeekID` + (2 + 1 + 8) `SeekPosition` + 2 + 1 `Seek`. */
            const val SEEK_HEAD_BYTES = 26

            /** 2 id + 1 size + 8 value — a 64-bit float, which is what ffmpeg reserves 11 bytes for. */
            const val DURATION_BYTES = 11

            /** Matroska's default, and what ffmpeg writes: one tick is one millisecond. */
            const val DEFAULT_TIMESTAMP_SCALE = 1_000_000L
            const val MIN_TIMESTAMP_SCALE = 1_000L
            const val MAX_TIMESTAMP_SCALE = 1_000_000_000L
            const val NANOS_PER_MILLI = 1_000_000L

            /**
             * ffmpeg writes about 22 bytes of cue per cluster and a cluster every five seconds, so a
             * megabyte of tail covers roughly a day of runtime.
             */
            const val CUES_SCAN_BYTES = 1L shl 20

            /**
             * Sixteen megabytes is far past any trailer a muxer writes behind its index — the point at
             * which "the `Cues` are not in this file" is a better answer than another read.
             */
            const val MAX_CUES_SCAN_BYTES = 16L shl 20

            /** Enough top-level elements for any real header; a guard against a walk that never ends. */
            const val MAX_HEADER_ELEMENTS = 64

            /** `Cues` is normally the last element; a handful more allows for `Tags` behind it. */
            const val MAX_TRAILING_ELEMENTS = 16

            /** An element id is one to four bytes. */
            const val MAX_ID_BYTES = 4

            /** 4 bytes of id plus 8 of size: the longest header this reads. */
            const val MAX_HEADER_BYTES = 12

            /** The shortest possible element: a one-byte id and a one-byte size. */
            const val MIN_HEADER_BYTES = 2

            /** A Void below ten bytes carries a one-byte length; at or above it, an eight-byte one. */
            const val VOID_LONG_FORM_MIN = 10
            const val VOID_SHORT_HEADER = 2
            const val VOID_LONG_HEADER = 9

            /** The top bit of a byte: an EBML varint's first width marker. */
            const val HIGH_BIT = 0x80

            /** One byte's worth of a `Long`, since Kotlin's `Byte` is signed. */
            const val BYTE_MASK = 0xFFL

            /** The widest EBML varint Matroska allows. */
            const val MAX_VARINT_BYTES = 8

            /**
             * ffmpeg's reservations are 152 and 11 bytes; nothing that behaves like one is near this.
             * A *declared* length beyond it comes from a file we did not make and is what every byte
             * count below is derived from — `toInt()` on a Void over two gigabytes wraps negative, and
             * the padding `ByteArray` sized from it either throws or allocates 2 GB, out of a coroutine
             * that was opening a file for playback.
             */
            const val MAX_VOID_BYTES = 64L * 1024L

            /**
             * Either the payload fills [total] exactly, or what is left has to be a Void of its own —
             * and the smallest Void there is, an id and a zero length, is two bytes.
             */
            fun fits(
                total: Long,
                payload: Int,
            ): Boolean = total <= MAX_VOID_BYTES && (total == payload.toLong() || total >= payload + VOID_SHORT_HEADER)

            /** The value bits of an EBML varint [length] bytes wide, every one of them set. */
            fun valueBits(length: Int): Long = (1L shl (length * Byte.SIZE_BITS - length)) - 1L

            /** A one-byte EBML length carrying [value] — valid for 0..126. */
            fun length(value: Int): ByteArray = byteArrayOf((HIGH_BIT or value).toByte())

            /** An eight-byte EBML length, the form ffmpeg uses to pad a Void to an exact size. */
            fun wideLength(value: Long): ByteArray {
                val bytes = bigEndian(value)
                bytes[0] = 0x01
                return bytes
            }

            fun wideLength(value: Int): ByteArray = wideLength(value.toLong())

            fun bigEndian(value: Long): ByteArray =
                ByteArray(MAX_VARINT_BYTES) { index ->
                    (value ushr ((MAX_VARINT_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
                }

            fun matches(
                buffer: ByteArray,
                at: Int,
                pattern: ByteArray,
            ): Boolean {
                if (at + pattern.size > buffer.size) return false
                for (offset in pattern.indices) {
                    if (buffer[at + offset] != pattern[offset]) return false
                }
                return true
            }
        }
    }
