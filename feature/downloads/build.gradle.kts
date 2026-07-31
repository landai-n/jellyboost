plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.downloads"
}

dependencies {
    // The Downloads screen is the one feature that talks to the download pipeline directly; every
    // other screen only reads `DownloadRepository.observeStates()` for its badges.
    implementation(projects.data.downloads)
    // For `@DefaultDispatcher` alone: the state projection is CPU work and must not run on Main
    // (audit PERF-03).
    implementation(projects.core.network)
    // The projection's `.catch` logs the failure it turns into an error state (audit STAB-10).
    implementation(libs.timber)
}
