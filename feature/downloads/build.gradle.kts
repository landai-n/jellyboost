plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.downloads"
}

dependencies {
    // The one feature that drives the download pipeline; every other screen only reads
    // `DownloadRepository.observeStates()` for its badges.
    implementation(projects.data.downloads)
    // No `:core:network` edge: `@DefaultDispatcher` lives in `:core:common`, which the feature
    // convention plugin already supplies.
    implementation(libs.timber)
}
