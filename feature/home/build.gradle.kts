plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.home"
}

dependencies {
    implementation(projects.data)
}
