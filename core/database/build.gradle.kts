plugins {
    alias(libs.plugins.jellyfinnative.android.library)
    alias(libs.plugins.jellyfinnative.android.hilt)
    alias(libs.plugins.jellyfinnative.android.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.jellyfinnative.core.database"
}

dependencies {
    api(projects.core.common)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
