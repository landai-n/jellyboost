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
    // No `:core:network` edge: the only reason for one would be `@DefaultDispatcher` (the state
    // projection is CPU work and must not run on Main), and that qualifier lives in `:core:common`,
    // which the feature convention plugin already supplies.
    //
    // The projection's `.catch` logs the failure it turns into an error state.
    implementation(libs.timber)
}
