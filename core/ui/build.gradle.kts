plugins {
    alias(libs.plugins.jellyboost.android.library.compose)
}

android {
    namespace = "dev.jellyboost.core.ui"
}

dependencies {
    api(projects.core.common)
    api(libs.androidx.compose.material.icons.extended)
    // `api`: `LocalHazeState` exposes `HazeState` in this module's public signatures.
    api(libs.haze)
    // `implementation`: Coil is confined to `JellyfinAsyncImage`'s body and named by no signature
    // here; `:app` and `:player` declare it themselves.
    implementation(libs.coil.compose)
    // Runtime only — nothing names a type from it; it is here so Coil's ServiceLoader finds an
    // OkHttp-backed network fetcher on every consumer's runtime classpath.
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)
}
