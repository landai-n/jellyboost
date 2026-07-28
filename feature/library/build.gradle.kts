plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.library"
}

dependencies {
    implementation(projects.data)
    implementation(libs.androidx.paging.compose)
}
