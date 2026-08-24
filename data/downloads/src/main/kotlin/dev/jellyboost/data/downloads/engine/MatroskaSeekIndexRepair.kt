package dev.jellyboost.data.downloads.engine

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes a transcoded download **seekable**, by writing the one index entry its muxer could not.
 *
 * ### The bug this exists for
 * A transcoded download is Matroska produced live by the server's ffmpeg and sent over a chunked
 * HTTP response (docs/features/download-quality.md, *"Why the container is mkv and not mp4"*).
 * ffmpeg cannot seek backwards in a pipe, so the file it lands on the device is missing exactly the
 * two things it would normally patch into the header at the end:
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
 * The **`Cues` are there** — ffmpeg holds them in memory and appends them in its trailer, which is
 * why a 23-minute episode ends with 698 cue points. Nothing points at them. Media3's
 * `MatroskaExtractor` only learns where `Cues` live from a `SeekHead`, and on reaching the first
 * `Cluster` without one it publishes `SeekMap.Unseekable`; `ProgressiveMediaPeriod.seekToUs` then
 * reads `positionUs = seekMap.isSeekable() ? positionUs : 0`, so **every** drag of the seek bar
 * restarts the episode from zero. That is the whole of the fault. An `ORIGINAL` download is a file
 * the server had already finished writing, carries a real `SeekHead` at the same offset 52, and
 * seeks correctly — which is why only transcoded downloads are affected.
 *
 * ### The repair
 * Write a 26-byte `SeekHead` naming the `Cues` into the Void that was reserved for it, and — when
 * the item's runtime is known — an 11-byte `Duration` into the Void reserved for *that*, inside
 * `Info`. Both land **inside Void elements**, which by definition carry no information: no byte that
 * means anything is ever overwritten, the file's length never changes, and every offset the `Cues`
 * already contain stays valid. From there Media3 does the rest natively — it seeks to the `Cues`,
 * parses them and builds its own `MatroskaSeekMap`, exactly as it does for any normal Matroska file.
 *
 * The alternative — teaching the player a custom `Extractor` that injects a `SeekMap` built from an
 * index we record during the download — was rejected: it needs a schema change, a bespoke `SeekMap`
 * and an `ExtractorOutput` wrapper to deliver a *worse* index than the one already sitting in the
 * file, and it would leave every download already on the device unseekable.
 *
 * ### Where it runs, and why there
 * From [dev.jellyboost.data.downloads.offline.DownloadedMediaProvider], the single gate every
 * offline playback passes through — not from the download pipeline. That is deliberate: downloads
 * already on the device were fetched without the repair, and repairing at first play fixes those
 * too. It is idempotent and cheap to repeat: a file that already has a
 * `SeekHead` (every `ORIGINAL` download, and every transcode after its first play) is recognised in
 * two reads of twelve bytes, and only a file that genuinely needs the repair pays the
 * [CUES_SCAN_BYTES] tail scan — once, ever.
 *
 * ### What it refuses to do
 * Every step is a veto, and a veto leaves the file byte-for-byte as it was:
 * - not Matroska → [Outcome.NOT_MATROSKA]; Matroska whose header does not step through →
 *   [Outcome.UNSUPPORTED_HEADER];
 * - a `SeekHead` already present → [Outcome.ALREADY_INDEXED], whatever it points at (second-guessing
 *   one would mean deciding we understand the file better than the tool that made it). The one thing
 *   that path still does is fill in a `Duration` the file is missing — see [Outcome.ALREADY_INDEXED];
 * - no `Cues` element ending exactly at the end of the file → [Outcome.NO_CUES] — a download
 *   interrupted before ffmpeg's trailer has no index to point at, and inventing one is not on offer;
 * - no reserved Void big enough → [Outcome.NO_ROOM]; growing the header would move every cluster in
 *   the file and invalidate every offset in the `Cues`. A Void *too* large is refused by the same
 *   rule: past a few kilobytes it is not a muxer's reservation, it is a length an untrusted file
 *   declared, and the byte counts derived from it would overflow (`MAX_VOID_BYTES`).
 *
 * After writing, the patched regions are read back and the header re-walked. If either disagrees
 * with what was intended the original bytes are put back, because a download that seeks badly is a
 * bug and one that no longer parses is a lost gigabyte.
 */
@Singleton
@Suppress(
    // A byte-level EBML walker over an untrusted file: every private helper here is the same shape — read a header,
    // bail out the moment the bytes stop making sense. Each early return names a distinct malformation, which is
    // precisely what must not be collapsed into one exit.
    "ReturnCount",
)
internal class MatroskaSeekIndexRepair
    @Inject
    constructor() {
        /** What [ensureSeekable] did, or why it did nothing. */
        enum class Outcome {
            /** A `SeekHead` was written; the file is now seekable. */
            INDEXED,

            /**
             * The file already had a `SeekHead` — an `ORIGINAL` download, or an earlier repair.
             *
             * A missing `Duration` is still filled in on this path; the seek index is what was found
             * already there, and it is the seek index this names.
             */
            ALREADY_INDEXED,

            /** Not a Matroska file: no `EBML` header, or no `Segment` behind it. */
            NOT_MATROSKA,

            /**
             * Matroska, but a header this cannot step through — so not one it may write into.
             *
             * The shape that reaches here is an unknown-size element other than the `Segment`, an
             * element id the spec forbids, or a length that runs past the end of the file. A live
             * remux writing unknown-size *clusters* would land here; no producer we feed does, and
             * the file is left exactly as it was either way. Told apart from [NOT_MATROSKA]
             * because "this is not a Matroska file" and "this is a Matroska file whose header we
             * decline to parse" are different things to read in a log.
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
         *
         * Blocking I/O — call it from a dispatcher that expects to block.
         *
         * @param runtimeMillis the item's runtime, used for the `Duration` element ffmpeg also left
         *   unwritten. Zero or negative simply skips that half; the seek index does not depend on it.
         */
        fun ensureSeekable(
            file: File,
            runtimeMillis: Long,
        ): Outcome = ensureSeekable(file, runtimeMillis, PatchWriter.Direct)

        /**
         * [ensureSeekable] with the seam a fault-injection test writes through — see [PatchWriter].
         *
         * Internal rather than a constructor parameter because the class is `@Inject`-constructed:
         * Hilt has nothing to bind a writer to, and a default argument would not change that.
         */
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
                    // This runs inside the coroutine that is opening a file for playback, and the
                    // input is a file off a server. A header we cannot make sense of is a file we
                    // decline to repair — never an exception thrown through the player.
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

            // The order is the crash-safety property, not a style: `Duration` first and the
            // `SeekHead` last, because each patch on its own leaves a file that still parses, and the
            // `SeekHead` is the marker that says "already repaired". A run torn between the two —
            // an IOException, a power cut — therefore leaves a file that is still playable and still
            // *re-repairable* on the next play, rather than one that is neither.
            val patches =
                buildList {
                    duration(media, header.info, runtimeMillis)?.let(::add)
                    add(Patch(reserved.start, seekHead(reserved.length.toInt(), cues - segment.contentStart)))
                }
            return if (write(media, length, segment.contentStart, patches, writer)) Outcome.INDEXED else Outcome.FAILED
        }

        /**
         * The `Duration` a file that is *already* indexed may still be missing.
         *
         * A `SeekHead` means the seek index needs nothing, but it must not end the run: a transcode
         * first opened before its runtime was known (an item played straight off a bare download
         * row) has its index and no `Duration`, and stopping at the `SeekHead` would leave it that
         * way however many times it is played afterwards — `Player.getDuration()`, the media
         * notification and PiP unset for the life of the file.
         *
         * The outcome is still [Outcome.ALREADY_INDEXED]: it describes the seek index, which this
         * did not touch. Only a write that would not stay written turns it into [Outcome.FAILED].
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
         * Applies [patches], then proves the result before believing it.
         *
         * The read-back catches a short write; the second header walk catches a patch that is
         * well-formed on its own but does not chain — the two ways a header can be left worse than it
         * was found. Either one puts the original bytes back.
         *
         * So does **anything thrown**. The original bytes are read before the first write and held in
         * memory for exactly as long as the file is in a half-patched state, and a write that dies
         * part-way has to be treated the same as one that verifies wrong: leaving the file as it lies
         * would cost the user a downloaded gigabyte that no longer parses, with a Room row still
         * saying `DOWNLOADED`. What this cannot cover is the process dying between the two — which
         * is what the patch order in [index] is for.
         *
         * @return whether the patches are on the platter and the header still walks.
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
                    // A short write is caught by the read-back; a patch that is well-formed but does
                    // not chain, by the walk.
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
                // Nothing more can be done from here: the bytes are known, the file will not take
                // them, and the outcome is a failure either way. Saying so loudly is the remedy.
                Timber.e(error, "Could not restore the original header; the file may no longer parse")
            }
            return false
        }

        /**
         * A `SeekHead` naming the `Cues`, padded with a Void to exactly [total] bytes.
         *
         * One entry, because one is all that is missing: Media3 reads a `SeekHead` only to find where
         * `Cues` live, and `Info`, `Tracks` and the clusters are all found by reading the file
         * forwards from the start, which is what every player does anyway.
         *
         * @param cuesPosition the `Cues` offset **relative to the Segment's content**, which is what
         *   `SeekPosition` means and what Media3 adds its own `segmentContentPosition` back onto.
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
         * is nothing to write or nowhere to put it.
         *
         * Written as a 64-bit float because that is exactly the 11 bytes ffmpeg's `put_ebml_void(11)`
         * reserves — a narrower form would fit too, but only the 8-byte one is guaranteed to have
         * somewhere to go, and supporting both buys nothing on a file shape we can see.
         *
         * Without it `ExoPlayer.duration` stays `TIME_UNSET`. The player's own UI already falls back
         * to the item's runtime, so this is not what fixes seeking — it is what makes the media
         * notification, PiP and `Player.getDuration()` agree with the seek bar.
         *
         * An `Info` carrying a **written** `CRC-32` is left alone: the checksum covers the Void this
         * would write into, so filling it in would leave a file a strict parser is entitled to reject.
         * The shape this repair exists for does not have one — ffmpeg leaves a six-byte Void where a
         * `CRC-32` would go, which is a Void and not that element. It matters most on the
         * duration back-fill: an already-indexed file is far likelier to be an `ORIGINAL` the
         * server checksummed than a transcode.
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
         * A Void element of exactly [total] bytes, in the same two forms ffmpeg's `put_ebml_void`
         * writes: a one-byte length below ten bytes, an eight-byte one above, which is why the
         * reserved Voids in a transcode look the way they do.
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
         * Walks the Segment's children up to the first cluster — everything that decides whether a
         * repair is needed and where it would go.
         *
         * A `SeekHead` does **not** end the walk, though it settles the seek index: `Info` sits
         * behind it in every file a muxer writes, and an already-indexed file may still be owed a
         * `Duration`. What a `SeekHead` does buy is the answer to the only question left if the
         * walk then breaks down — the file is indexed — so that case reports the index it found and
         * nothing else, and nothing is written.
         *
         * @return `null` when the header does not parse and no `SeekHead` was reached, which is a
         *   file this has no business writing to.
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
         * The offset of the `Cues` element, found by scanning the tail of the file for its id.
         *
         * A candidate is only believed when the chain of elements starting at it lands **exactly** on
         * the end of the file — the property that makes a stray `1C 53 BB 6B` inside compressed frame
         * data essentially impossible to accept, since it would have to be followed by a size varint
         * that happens to reach the last byte of the file.
         *
         * Scanning backwards from the end is what makes this affordable: walking forwards would mean
         * a seek per cluster, thousands of them, and the `Cues` are the last thing in the file.
         *
         * It scans in [CUES_SCAN_BYTES] windows rather than in one, because "the `Cues` are near the
         * end" is a rule of thumb and not a guarantee: `Tags`, `Chapters` or an attachment written
         * behind them push them arbitrarily far back, and a single window would silently report
         * [Outcome.NO_CUES] for a file whose index sits a megabyte and one byte from the end.
         * Windows overlap by an id width so a `Cues` id split between two of them is still seen, and
         * the whole search is bounded by [MAX_CUES_SCAN_BYTES] — past that this is not looking for a
         * trailer any more, it is reading the film.
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

        /** Whether the elements starting at [at] tile the rest of the file exactly. */
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
         * One element header at [at], bounded by [limit].
         *
         * @param unknownSizeRunsToEnd whether the "every value bit set" size sentinel should be read
         *   as "to the end of [limit]" instead of being refused. True only for the Segment: anything
         *   else with an unknown size cannot be stepped over, and a header this cannot step through is
         *   a header it must not write into.
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
         * The element id at the head of [header] — one to four bytes, kept as its own integer.
         *
         * The two encodings RFC 8794 §5 forbids are refused rather than stepped over: value bits all
         * zero is a longer spelling of a shorter id, and value bits all one is the reserved id no
         * element may carry. Both are `null` here, which vetoes the whole header — a file that
         * declares an id the format says cannot exist is not one to write into, whether or not the
         * bytes behind it happen to tile.
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

        /** One element, with both of the offsets the walk needs. */
        private data class Element(
            val id: Long,
            val start: Long,
            val contentStart: Long,
            val size: Long,
        ) {
            /** The first byte past this element. */
            val end: Long get() = contentStart + size

            /** Id, size and content together — what a Void has to spare. */
            val length: Long get() = contentStart - start + size
        }

        /** A decoded EBML variable-width integer, or an element id. */
        private data class VarInt(
            val value: Long,
            val length: Int,
            val unknown: Boolean,
        )

        /** What the walk up to the first cluster found. */
        private class Header(
            val seekHead: Element?,
            val void: Element?,
            val info: Element?,
        )

        /** Bytes to put at an offset. */
        private class Patch(
            val start: Long,
            val bytes: ByteArray,
        )

        /**
         * The one way bytes reach the file, so that a test can make a write fail where it hurts.
         *
         * The rollback path is unreachable from the outside — it needs an I/O error to happen between
         * two writes, on the one file the code is holding open — and it is also the path that decides
         * whether a failed repair costs the user a download. A seam is the only way to pin it.
         */
        internal fun interface PatchWriter {
            fun write(
                media: RandomAccessFile,
                at: Long,
                bytes: ByteArray,
            )

            companion object {
                /** What production uses: seek, write, and nothing else. */
                val Direct =
                    PatchWriter { media, at, bytes ->
                        media.seek(at)
                        media.write(bytes)
                    }
            }
        }

        private companion object {
            /** `EBML`, the header every Matroska file opens with. */
            const val ID_EBML_VALUE = 0x1A45DFA3L

            /** `Segment`. */
            const val ID_SEGMENT_VALUE = 0x18538067L

            /** `SeekHead` — the element this writes, and whose presence means there is nothing to do. */
            const val ID_SEEK_HEAD_VALUE = 0x114D9B74L
            val ID_SEEK_HEAD = byteArrayOf(0x11, 0x4D, 0x9B.toByte(), 0x74)

            /** `Seek`, one entry of a `SeekHead`. */
            val ID_SEEK = byteArrayOf(0x4D, 0xBB.toByte())

            /** `SeekID`, the id of the element an entry points at. */
            val ID_SEEK_ID = byteArrayOf(0x53, 0xAB.toByte())

            /** `SeekPosition`, relative to the Segment's content. */
            val ID_SEEK_POSITION = byteArrayOf(0x53, 0xAC.toByte())

            /** `Cues`, the index ffmpeg does write — at the end, where nothing points at it. */
            val ID_CUES = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)

            /** `Info`, which holds `TimestampScale` and the Void reserved for `Duration`. */
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
             * How much of the tail to search for the `Cues` id.
             *
             * ffmpeg writes about 22 bytes of cue per cluster and a cluster every five seconds, so a
             * megabyte covers roughly a day of runtime — and it is read at most once per file, since
             * a file that gets its `SeekHead` never reaches this code again.
             */
            const val CUES_SCAN_BYTES = 1L shl 20

            /**
             * How far back from the end the search for `Cues` may reach in total.
             *
             * Sixteen megabytes is far past any trailer a muxer writes behind its index — it is the
             * point at which "the `Cues` are not in this file" is a better answer than another read.
             * It only ever costs a file that genuinely has no index, and that file is refused once
             * and then plays exactly as it did before.
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
             * The largest Void this will write into.
             *
             * A reservation is a muxer setting aside room for a header it means to come back and
             * write: ffmpeg's are 152 and 11 bytes, and nothing that behaves like a reservation is
             * anywhere near this. A *declared* length beyond it is a number in a file we did not
             * make, and it is also the number every one of the byte counts below is derived from —
             * `toInt()` on a Void of more than two gigabytes wraps negative, and the padding
             * `ByteArray` sized from it is either a `NegativeArraySizeException` or a two-gigabyte
             * allocation, thrown out of a coroutine that was opening a file for playback. Refusing
             * the Void instead costs a file that could not have been a live transcode nothing but a
             * repair it never needed.
             */
            const val MAX_VOID_BYTES = 64L * 1024L

            /**
             * Whether [total] bytes of Void can hold [payload] bytes and still be legal.
             *
             * Either the payload fills it exactly, or what is left has to be a Void of its own — and
             * the smallest Void there is, an id and a zero length, is two bytes. A Void larger than
             * [MAX_VOID_BYTES] is refused whatever it would hold: see there.
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

            /** [value] as eight big-endian bytes. */
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
