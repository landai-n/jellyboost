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
    // The third-party licence list. `:app` owns the generated JSON (its dependency graph is the one
    // that ships); this module only renders whichever raw resource it is handed.
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.timber)
}
