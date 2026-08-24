plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.detail"
}

dependencies {
    implementation(projects.data)
    // The Download button enqueues and deletes through `DownloadRepository`.
    implementation(projects.data.downloads)
    // `BackHandler`: system Back leaves batch-selection mode before it pops the destination.
    implementation(libs.androidx.activity.compose)
    // The badge collector logs when it degrades to no badges.
    implementation(libs.timber)
}
