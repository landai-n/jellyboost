plugins {
    alias(libs.plugins.jellyfinnative.android.feature)
}

android {
    namespace = "dev.jellyfinnative.feature.search"
}

dependencies {
    implementation(projects.data)
}
