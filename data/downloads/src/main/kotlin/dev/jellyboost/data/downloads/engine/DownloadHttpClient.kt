package dev.jellyboost.data.downloads.engine

import javax.inject.Qualifier

/**
 * Marks the OkHttp client the download engine transfers on. Declared beside its one consumer rather
 * than next to the module that provides it, which is what lets `.engine` stay upstream of `.di` in the
 * module's package DAG (see `PackageDependencyTest`).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DownloadHttpClient
