plugins {
    alias(libs.plugins.jellyfinnative.android.library)
    alias(libs.plugins.jellyfinnative.android.hilt)
}

android {
    namespace = "dev.jellyfinnative.core.network"
}

dependencies {
    api(projects.core.common)
    api(libs.jellyfin.sdk)
    api(libs.okhttp)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
}
