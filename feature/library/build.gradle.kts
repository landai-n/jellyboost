plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.library"
}

dependencies {
    implementation(projects.data)
    // Read-only: `DownloadRepository.observeStates()` drives the badge on every card (M7).
    implementation(projects.data.downloads)
    implementation(libs.androidx.paging.compose)
    // `BackHandler`: system Back leaves batch-selection mode before it pops the destination.
    implementation(libs.androidx.activity.compose)
    // The badge collector logs when it degrades to no badges (audit STAB-10).
    implementation(libs.timber)
}
