package dev.jellyfinnative.data.downloads.engine

import javax.inject.Qualifier

/**
 * Marks the OkHttp client the download engine transfers on.
 *
 * Declared beside its one consumer ([FileDownloader]) rather than next to the module that provides
 * it: a qualifier the engine reads is part of the engine's contract, and keeping it here is what
 * lets `.engine` stay upstream of `.di` in the module's package DAG (audit ARCH-05 — see
 * `PackageDependencyTest`). The binding itself still lives in `di.DownloadHttpModule`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient
