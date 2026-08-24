plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.home"
}

dependencies {
    implementation(projects.data)
    // Read-only: `DownloadRepository.observeStates()` drives the badge on every card.
    implementation(projects.data.downloads)
    // The badge collector logs when it degrades to no badges.
    implementation(libs.timber)
}
