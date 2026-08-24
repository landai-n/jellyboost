package dev.jellyboost.data.downloads.engine

/**
 * How much **media time** a Matroska byte stream has delivered so far, computed from the bytes as
 * they arrive.
 *
 * A progressive transcode is chunked with no `Content-Length`, but Matroska writes an absolute
 * timestamp at the head of every cluster (ffmpeg's muxer emits one roughly every 5 s or 5 MB), and
 * `bytesReceived × runtime / mediaTimeReceived` is the average output bitrate so far — which is the
 * number [TranscodeSizeProjector] needs.
 *
 * `TimestampScale` (`0x2AD7B1`) is read only *before* the first cluster, where Matroska puts Segment
 * Info, so a byte triple occurring inside later frame data can never move it; absent or implausible
 * leaves the spec default of 1 000 000 ns — one tick = 1 ms, which is what ffmpeg writes.
 *
 * A four-byte pattern occurs by chance roughly every 4 GB of random data, so a `Cluster` candidate is
 * believed only when all of this holds — these are the numbers the guards below cite:
 * 1. the cluster's size is a well-formed EBML varint of 1–8 bytes;
 * 2. that size is the "unknown size" sentinel (legal, and what a live mux writes) or lies in
 *    [MIN_CLUSTER_BYTES]..[MAX_CLUSTER_BYTES];
 * 3. its first child is `Timestamp` (`0xE7`), optionally behind a `CRC-32` (`0xBF`) — the spec
 *    requires `CRC-32` to be the first child of whatever contains it and ffmpeg writes one per
 *    cluster, so insisting `Timestamp` be first reads zero clusters off a real server response;
 * 4. the timestamp's own size is a one-byte varint in `0x81..0x88`;
 * 5. every one of those bytes is present — a candidate running off the end of the chunk is dropped
 *    whole and retried through the carry buffer, never half-consumed;
 * 6. the decoded value is non-negative and scales to at most [MAX_MEDIA_MILLIS];
 * 7. it is **not before** the newest time already accepted: ffmpeg writes the file linearly, so a
 *    timestamp going backwards is a false positive rather than a rewind.
 *
 * [consume] is fed the same buffers `FileDownloader` writes to disk, so an element can straddle two
 * of them: the last [WINDOW] − 1 bytes of every chunk are carried forward and re-scanned joined to
 * the head of the next. Re-scanning the overlap is harmless — only the newest timestamp is kept.
 *
 * Not thread-safe: one instance belongs to one file transfer, on the one thread copying it.
 */
@Suppress(
    "ReturnCount",
)
internal class MkvClusterScanner {
    private val carry = ByteArray(WINDOW - 1)
    private var carryLength = 0

    /**
     * Scratch for the carry-plus-head join, allocated once and reused: a fresh `ByteArray` per chunk
     * here, plus a second in [rememberTail], would be two allocations for every 64 KB of a transfer.
     */
    private val joined = ByteArray(2 * (WINDOW - 1))

    private var timestampScaleNanos = DEFAULT_TIMESTAMP_SCALE_NANOS
    private var sawCluster = false
    private var latestMillis = -1L

    /**
     * Media milliseconds delivered so far, or `null` until a first cluster timestamp has been read
     * *and* believed. It is the timestamp of the newest cluster **started**, so it understates what
     * has arrived — which makes the projection built on it generous, the safe direction for a figure
     * shown next to a progress bar.
     */
    val mediaMillisReceived: Long? get() = latestMillis.takeIf { it >= 0L }

    /** Bytes must arrive in order and without gaps, which is why only the download's copy loop drives this. */
    fun consume(
        chunk: ByteArray,
        offset: Int = 0,
        length: Int = chunk.size,
    ) {
        if (length <= 0) return

        // Candidates beginning in the carried bytes, joined to enough of this chunk to complete them.
        if (carryLength > 0) {
            val take = minOf(length, WINDOW - 1)
            System.arraycopy(carry, 0, joined, 0, carryLength)
            System.arraycopy(chunk, offset, joined, carryLength, take)
            scan(joined, from = 0, end = carryLength + take, startEnd = carryLength)
        }

        scan(chunk, from = offset, end = offset + length, startEnd = offset + length)
        rememberTail(chunk, offset, length)
    }

    /**
     * Scans `[from, end)` for candidates that *begin* before [startEnd]. The two ids have distinct
     * lead bytes, so one byte test per offset replaces two [matches] calls and two bounds checks for
     * each byte of a multi-gigabyte transfer, with identical results.
     */
    private fun scan(
        buffer: ByteArray,
        from: Int,
        end: Int,
        startEnd: Int,
    ) {
        var index = from
        while (index < startEnd) {
            when (buffer[index]) {
                CLUSTER_LEAD ->
                    if (matches(buffer, index, end, CLUSTER_ID)) {
                        readClusterMillis(buffer, index + CLUSTER_ID.size, end)?.let(::acceptCluster)
                    }

                TIMESTAMP_SCALE_LEAD ->
                    if (!sawCluster && matches(buffer, index, end, TIMESTAMP_SCALE_ID)) {
                        readTimestampScale(buffer, index + TIMESTAMP_SCALE_ID.size, end)
                    }

                else -> Unit
            }
            index++
        }
    }

    /**
     * Keeps the tail of `carry + chunk` for the next call, shifted in place: `System.arraycopy` is a
     * `memmove`, so the surviving carry bytes can slide down over themselves.
     */
    private fun rememberTail(
        chunk: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val keep = minOf(WINDOW - 1, carryLength + length)
        val fromChunk = minOf(length, keep)
        val fromCarry = keep - fromChunk
        if (fromCarry > 0) System.arraycopy(carry, carryLength - fromCarry, carry, 0, fromCarry)
        System.arraycopy(chunk, offset + length - fromChunk, carry, fromCarry, fromChunk)
        carryLength = keep
    }

    /**
     * Rules 1–6 for a cluster; [start] is the index just past the cluster id.
     *
     * @return the cluster's start time in media milliseconds, or `null` when anything failed to check
     *   out — including "not all the bytes are here yet", which the carry buffer retries.
     */
    private fun readClusterMillis(
        buffer: ByteArray,
        start: Int,
        end: Int,
    ): Long? {
        // 1. The cluster's own size.
        val size = readVarInt(buffer, start, end) ?: return null
        // 2. Unknown-size clusters are legal; a known size has to be able to hold a timestamp at all.
        if (!size.unknown && (size.value < MIN_CLUSTER_BYTES || size.value > MAX_CLUSTER_BYTES)) return null

        // 3. `Timestamp` must be the first child, after an optional `CRC-32` — the shape ffmpeg
        //    writes, and the reason a false positive is unlikely.
        var index = skipCrc32(buffer, start + size.length, end) ?: return null
        if (index >= end || (buffer[index].toInt() and BYTE_MASK) != TIMESTAMP_ID) return null
        index++

        // 4. Its length, as a one-byte varint carrying 1..8.
        if (index >= end) return null
        val lengthByte = buffer[index].toInt() and BYTE_MASK
        if (lengthByte < MIN_VALUE_LENGTH_BYTE || lengthByte > MAX_VALUE_LENGTH_BYTE) return null
        val valueLength = lengthByte and VARINT_VALUE_MASK
        index++

        // 5. All of it has to be present.
        if (index + valueLength > end) return null
        val ticks = readUnsigned(buffer, index, valueLength) ?: return null

        // 6. Scale it, refusing anything that would overflow or is plainly not a media timestamp.
        if (ticks > Long.MAX_VALUE / timestampScaleNanos) return null
        val millis = ticks * timestampScaleNanos / NANOS_PER_MILLI
        return millis.takeIf { it <= MAX_MEDIA_MILLIS }
    }

    /**
     * Reads `TimestampScale` (nanoseconds per tick). Only ever called before the first cluster, so
     * frame data cannot fool it. The size is read as a full varint, not one byte: nothing says a muxer
     * must spell "3" as `0x83` when `0x40 0x03` is equally legal.
     */
    private fun readTimestampScale(
        buffer: ByteArray,
        start: Int,
        end: Int,
    ) {
        val size = readVarInt(buffer, start, end) ?: return
        if (size.unknown || size.value < 1L || size.value > MAX_VARINT_LENGTH) return
        val valueLength = size.value.toInt()
        if (start + size.length + valueLength > end) return

        val scale = readUnsigned(buffer, start + size.length, valueLength) ?: return
        // A scale outside this range is not a file we can reason about; keep the default.
        if (scale in MIN_TIMESTAMP_SCALE_NANOS..MAX_TIMESTAMP_SCALE_NANOS) timestampScaleNanos = scale
    }

    private fun acceptCluster(millis: Long) {
        sawCluster = true
        // 7. A timestamp that goes backwards is a false positive, not a rewind.
        if (millis >= latestMillis) latestMillis = millis
    }

    private companion object {
        val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)

        /** `TimestampScale`, inside Segment Info. */
        val TIMESTAMP_SCALE_ID = byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte())

        /** Distinct, which is what lets one `when` decide which pattern an offset could begin. */
        val CLUSTER_LEAD = CLUSTER_ID[0]
        val TIMESTAMP_SCALE_LEAD = TIMESTAMP_SCALE_ID[0]

        /** `Timestamp`, the first child of a cluster once any `CRC-32` is past. */
        const val TIMESTAMP_ID = 0xE7

        /** Matroska requires `CRC-32` to be its parent's first child, and ffmpeg writes one per cluster. */
        const val CRC32_ID = 0xBF

        /** A CRC-32 is four bytes, and the spec allows it to be spelled no other way. */
        const val CRC32_BYTES = 4

        /** `0x84` — a one-byte varint carrying [CRC32_BYTES]. */
        const val CRC32_LENGTH_BYTE = 0x84

        /** Matroska's default, and what ffmpeg writes: one tick is one millisecond. */
        const val DEFAULT_TIMESTAMP_SCALE_NANOS = 1_000_000L

        /** 1 µs — finer than any muxer uses, and the floor below which the value is nonsense. */
        const val MIN_TIMESTAMP_SCALE_NANOS = 1_000L

        /** 1 s per tick — coarser than any real file, and the ceiling for the same reason. */
        const val MAX_TIMESTAMP_SCALE_NANOS = 1_000_000_000L

        const val NANOS_PER_MILLI = 1_000_000L

        /** 48 hours: longer than any item in a library, so anything above it is a bad parse. */
        const val MAX_MEDIA_MILLIS = 48L * 60L * 60L * 1_000L

        /** A cluster has to hold at least its own timestamp element. */
        const val MIN_CLUSTER_BYTES = 3L

        /** ffmpeg caps clusters at a few MB; 256 MB is far past any real one. */
        const val MAX_CLUSTER_BYTES = 256L * 1024L * 1024L

        /** `0x81` — a one-byte varint carrying the value 1. */
        const val MIN_VALUE_LENGTH_BYTE = 0x81

        /** `0x88` — a one-byte varint carrying the value 8, the widest integer Matroska stores. */
        const val MAX_VALUE_LENGTH_BYTE = 0x88

        /** The value bits of a one-byte EBML varint. */
        const val VARINT_VALUE_MASK = 0x7F

        /** One byte's worth of an `Int`, since Kotlin's `Byte` is signed. */
        const val BYTE_MASK = 0xFF

        /** The top bit of a byte: an EBML varint's first width marker, and a sign trap. */
        const val HIGH_BIT = 0x80

        /**
         * The longest candidate: 4 id + 8 size + 1 `CRC-32` id + 1 its size + 8 its value + 1 child
         * id + 1 child size + 8 value.
         */
        const val WINDOW = 32

        /** The widest EBML varint Matroska allows. */
        const val MAX_VARINT_LENGTH = 8

        /**
         * Steps over a cluster's `CRC-32` child. Its length has to be exactly four (`0x84`): accepting
         * 1..8 the way a general integer would is one fewer byte that has to line up for a random
         * `1F 43 B6 75` to be believed.
         *
         * @return the first child that is not a `CRC-32`, or `null` when one is there but malformed or
         *   not yet fully arrived, so the carry buffer retries the whole candidate.
         */
        fun skipCrc32(
            buffer: ByteArray,
            at: Int,
            end: Int,
        ): Int? {
            if (at >= end || (buffer[at].toInt() and BYTE_MASK) != CRC32_ID) return at
            if (at + 1 >= end) return null
            if ((buffer[at + 1].toInt() and BYTE_MASK) != CRC32_LENGTH_BYTE) return null
            return at + 2 + CRC32_BYTES
        }

        fun matches(
            buffer: ByteArray,
            at: Int,
            end: Int,
            pattern: ByteArray,
        ): Boolean {
            if (at + pattern.size > end) return false
            for (offset in pattern.indices) {
                if (buffer[at + offset] != pattern[offset]) return false
            }
            return true
        }

        /** Big-endian unsigned integer of [length] bytes, or `null` when it would not fit a [Long]. */
        fun readUnsigned(
            buffer: ByteArray,
            at: Int,
            length: Int,
        ): Long? {
            // Eight bytes with the top bit set does not fit a signed Long; nothing real is that big.
            if (length == MAX_VARINT_LENGTH && (buffer[at].toInt() and HIGH_BIT) != 0) return null
            var value = 0L
            for (offset in 0 until length) {
                value = (value shl Byte.SIZE_BITS) or (buffer[at + offset].toLong() and BYTE_MASK.toLong())
            }
            return value
        }

        /** @return `null` when the encoding is invalid (rule 1) or the bytes are not all present. */
        fun readVarInt(
            buffer: ByteArray,
            at: Int,
            end: Int,
        ): VarInt? {
            if (at >= end) return null
            val lead = buffer[at].toInt() and BYTE_MASK
            // A zero lead byte would mean a width above the eight bytes Matroska allows.
            if (lead == 0) return null

            var length = 1
            var mask = HIGH_BIT
            while (lead and mask == 0) {
                length++
                mask = mask shr 1
            }
            if (at + length > end) return null

            var value = (lead and (mask - 1)).toLong()
            for (offset in 1 until length) {
                value = (value shl Byte.SIZE_BITS) or (buffer[at + offset].toLong() and BYTE_MASK.toLong())
            }
            // "Unknown size" is every value bit set — legal, and what a stream being muxed live uses.
            val allOnes = (1L shl (length * Byte.SIZE_BITS - length)) - 1L
            return VarInt(value = value, length = length, unknown = value == allOnes)
        }
    }

    private data class VarInt(
        val value: Long,
        val length: Int,
        val unknown: Boolean,
    )
}
