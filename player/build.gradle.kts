plugins {
    alias(libs.plugins.jellyboost.android.library.compose)
    alias(libs.plugins.jellyboost.android.hilt)
}

android {
    namespace = "dev.jellyboost.player"
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.network)
    // M9: the segment-skip and picture-in-picture preferences (DECISIONS.md 2026-07-29).
    implementation(projects.core.datastore)
    implementation(projects.data)
    implementation(projects.data.downloads)

    // `implementation`, not `api`: after the ARCH-2 visibility sweep no public declaration in this
    // module names a Media3 type — `PlayerViewModel.videoPlayer` (a `StateFlow<Player?>`) was the
    // last one and is `internal` now — so `:app` compiles against none of them (audit ARCH-10).
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.cast)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)

    // M12 Chromecast. `media3-cast` brings the last three transitively; they are declared because
    // this module names their types directly (CastContext/CastOptions, MediaRouteButton, and the
    // AppCompat theme the MediaRouter dialogs need).
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.timber)

    // `TrickplayPreview` builds its own `ImageRequest` (a token-stripped cache key, DECISIONS.md
    // 2026-07-30/SEC-02), so it names `coil3` types directly. Declared here since `:core:ui`
    // stopped exporting Coil as `api` (audit ARCH-9).
    implementation(libs.coil.compose)
}
