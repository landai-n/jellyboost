plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.music"
}

dependencies {
    implementation(projects.data)
    // Download badges on track/album rows (docs/notes/music-m13-plan.md, Phase 2 note — the
    // download *pipeline* itself is Phase 5; this only reads `DownloadRepository.observeStates()`).
    implementation(projects.data.downloads)
    // The Albums/Artists/Playlists tabs each page over `getItemsPaged` (precedent: `:feature:library`).
    implementation(libs.androidx.paging.compose)
    implementation(libs.timber)
}
