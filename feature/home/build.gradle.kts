plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.home"
}

dependencies {
    implementation(projects.data)
    // Read-only: `DownloadRepository.observeStates()` drives the badge on every card (M7).
    implementation(projects.data.downloads)
    // The badge collector logs when it degrades to no badges (audit STAB-10).
    implementation(libs.timber)
}
