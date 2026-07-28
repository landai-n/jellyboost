plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.downloads"
}

dependencies {
    // The Downloads screen is the one feature that talks to the download pipeline directly; every
    // other screen only reads `DownloadRepository.observeStates()` for its badges.
    implementation(projects.data.downloads)
}
