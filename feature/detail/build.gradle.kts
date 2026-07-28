plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.detail"
}

dependencies {
    implementation(projects.data)
    // The Download button enqueues and deletes through `DownloadRepository` (M7).
    implementation(projects.data.downloads)
}
