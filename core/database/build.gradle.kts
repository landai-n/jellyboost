plugins {
    alias(libs.plugins.jellyboost.android.library)
    alias(libs.plugins.jellyboost.android.hilt)
    alias(libs.plugins.jellyboost.android.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.jellyboost.core.database"
}

dependencies {
    api(projects.core.common)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
