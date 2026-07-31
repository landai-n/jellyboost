plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.auth"
}

dependencies {
    implementation(projects.core.network)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.timber)
}
