plugins {
    alias(libs.plugins.jellyboost.android.library.compose)
}

android {
    namespace = "dev.jellyboost.core.ui"
}

dependencies {
    api(projects.core.common)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)
}
