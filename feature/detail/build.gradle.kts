plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.detail"
}

dependencies {
    implementation(projects.data)
}
