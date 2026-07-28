plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.auth"
}

dependencies {
    implementation(projects.core.network)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.timber)
}
