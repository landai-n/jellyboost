package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.database.entities.DownloadEntity
import java.io.File

// The test doubles the DownloadQueue drain tests are built on — the SyncPlayTestDoubles shape
// (player/src/test/.../syncplay/SyncPlayTestDoubles.kt).
//
// One FakeExtractor and one RecordingListener for every DownloadQueue*Test in this package, rather
// than a per-test double carrying its own subset of the same two behaviors: call recording (for the
// tests that check *what* was stripped or reported) and failure injection (for the tests that check
// what a strip failure costs). A test that never reads `calls`/`progress`/`idleCount` or sets
// `failure` behaves exactly as a bare no-op double would.

/**
 * The strip stage, without a `Looper`, a muxer or a device.
 *
 * A failing extractor still writes something first, because that is what a real one does: the
 * `Transformer` opens its output before it discovers it cannot finish, and the half-file it
 * leaves is precisely what the queue has to clean up.
 *
 * [sidecarBytes] is the caller's own `SIDECAR_BYTES` constant — every test in this package sizes
 * its fixture sidecar differently, so it is passed in rather than assumed.
 */
internal class FakeExtractor(
    private val sidecarBytes: Long = 400L,
) : AudioSidecarExtractor {
    /** Every (source, target) pair asked for, oldest first — empty unless a test reads it. */
    val calls = mutableListOf<Pair<File, File>>()

    /** Thrown by the next call, after the partial write a real failed strip leaves behind. */
    var failure: Exception? = null

    override suspend fun extract(
        source: File,
        target: File,
    ) {
        calls += source to target
        failure?.let { error ->
            target.writeBytes(ByteArray(1))
            throw error
        }
        target.writeBytes(ByteArray(sidecarBytes.toInt()))
    }
}

/** Records what the worker would have shown in its notification. */
internal class RecordingListener : DownloadQueueListener {
    /** Every (bytesDownloaded, bytesTotal) pair reported, oldest first — empty unless read. */
    val progress = mutableListOf<Pair<Long, Long>>()

    var idleCount = 0
        private set

    override suspend fun onProgress(
        download: DownloadEntity,
        bytesDownloaded: Long,
        bytesTotal: Long,
    ) {
        progress += bytesDownloaded to bytesTotal
    }

    override suspend fun onIdle() {
        idleCount++
    }
}
