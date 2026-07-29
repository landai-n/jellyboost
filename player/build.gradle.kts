plugins {
    alias(libs.plugins.jellyfinnative.android.library.compose)
    alias(libs.plugins.jellyfinnative.android.hilt)
}

android {
    namespace = "dev.jellyfinnative.player"
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.network)
    // M9: the segment-skip and picture-in-picture preferences (DECISIONS.md 2026-07-29).
    implementation(projects.core.datastore)
    implementation(projects.data)
    implementation(projects.data.downloads)

    api(libs.androidx.media3.common)
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)

    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.timber)
}
