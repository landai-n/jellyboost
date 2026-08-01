plugins {
    alias(libs.plugins.jellyboost.android.library.compose)
}

android {
    namespace = "dev.jellyboost.core.ui"
}

dependencies {
    api(projects.core.common)
    api(libs.androidx.compose.material.icons.extended)
    // `api`, not `implementation`: the glass helpers in `theme/GlassDefaults.kt` expose Haze types
    // (`HazeState` through `LocalHazeState`), so every module that provides or consumes a backdrop
    // needs them on its own compile classpath.
    api(libs.haze)
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)
}
