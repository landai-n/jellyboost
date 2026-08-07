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
    // No `:core:network` edge: this module's only reason for one was `@DefaultDispatcher` (the state
    // projection is CPU work and must not run on Main — audit PERF-03), and that qualifier now lives
    // in `:core:common`, which the feature convention plugin already supplies (audit ARCH-1).
    //
    // The projection's `.catch` logs the failure it turns into an error state (audit STAB-10).
    implementation(libs.timber)
}
