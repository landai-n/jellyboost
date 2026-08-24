plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.library"
}

dependencies {
    implementation(projects.data)
    implementation(projects.data.downloads)
    implementation(libs.androidx.paging.compose)
    // `BackHandler`: system Back leaves batch-selection mode before it pops the destination.
    implementation(libs.androidx.activity.compose)
    implementation(libs.timber)
}
