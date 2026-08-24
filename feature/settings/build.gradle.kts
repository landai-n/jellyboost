plugins {
    alias(libs.plugins.jellyboost.android.feature)
}

android {
    namespace = "dev.jellyboost.feature.settings"
}

dependencies {
    // Settings is the one screen that edits the preference store directly rather than through a
    // repository: every key it writes belongs to a different consumer (the player, the download
    // scheduler, the connection provider) and none of them wants an "update my setting" API.
    // Same shape as `:player`'s dependency on `:core:datastore`.
    implementation(projects.core.datastore)
    // The Account section signs out, which is `SessionRepository`'s job.
    implementation(projects.core.network)
    // Sign-out can optionally take the downloads with it, and the Downloads section reports how
    // much of the device they occupy.
    implementation(projects.data.downloads)
    implementation(libs.timber)
}
