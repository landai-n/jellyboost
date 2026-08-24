plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.music"
}

dependencies {
    implementation(projects.data)
    // Download badges on track/album rows: this module only reads
    // `DownloadRepository.observeStates()`, never drives the download pipeline itself.
    implementation(projects.data.downloads)
    // The Albums/Artists/Playlists tabs each page over `getItemsPaged` (precedent: `:feature:library`).
    implementation(libs.androidx.paging.compose)
    implementation(libs.timber)
}
