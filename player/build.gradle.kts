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
    // The segment-skip and picture-in-picture preferences live in the datastore module.
    implementation(projects.core.datastore)
    implementation(projects.data)
    implementation(projects.data.downloads)

    // `implementation`, not `api`: no public declaration in this module names a Media3 type —
    // `PlayerViewModel.videoPlayer` (a `StateFlow<Player?>`) is `internal` — so `:app` compiles
    // against none of them.
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.cast)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)

    // Chromecast. `media3-cast` brings the last three transitively; they are declared because
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

    // `TrickplayPreview` builds its own `ImageRequest` (a token-stripped cache key), so it names
    // `coil3` types directly. Declared here since `:core:ui` does not export Coil as `api`.
    implementation(libs.coil.compose)
}
