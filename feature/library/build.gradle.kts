plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.library"
}

dependencies {
    implementation(projects.data)
    // Read-only: `DownloadRepository.observeStates()` drives the badge on every card.
    implementation(projects.data.downloads)
    implementation(libs.androidx.paging.compose)
    // `BackHandler`: system Back leaves batch-selection mode before it pops the destination.
    implementation(libs.androidx.activity.compose)
    // The badge collector logs when it degrades to no badges.
    implementation(libs.timber)
}
