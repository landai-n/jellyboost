plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.search"
}

dependencies {
    implementation(projects.data)
    implementation(projects.data.downloads)
    implementation(libs.timber)
}
