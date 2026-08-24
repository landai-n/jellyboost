plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.settings"
}

dependencies {
    // Settings edits the preference store directly: every key it writes belongs to a different
    // consumer, and none of them wants an "update my setting" API.
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    implementation(projects.data.downloads)
    implementation(libs.timber)
}
