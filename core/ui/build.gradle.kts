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
    // `implementation`, unlike Haze above: Coil is confined to `JellyfinAsyncImage`'s body — the
    // only file in this module that imports `coil3` — and no public signature here names a Coil
    // type. `:app` and `:player` do name them, and say so themselves instead of inheriting them
    // through this module's compile classpath.
    implementation(libs.coil.compose)
    // Runtime only, at both ends: nothing names a type from it, and it is on the classpath so
    // Coil's ServiceLoader finds an OkHttp-backed network fetcher. `implementation` still puts it
    // on every consumer's *runtime* classpath, which is the whole requirement.
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)
}
