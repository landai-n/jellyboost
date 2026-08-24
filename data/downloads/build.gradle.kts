plugins {
    alias(libs.plugins.jellyboost.android.library)
    alias(libs.plugins.jellyboost.android.hilt)
}

android {
    namespace = "dev.jellyboost.data.downloads"

    buildFeatures {
        // The foreground notification's channel name, title and actions are strings.
        androidResources = true
    }
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    // `implementation`, not `api`: nothing in this module's *public* surface names a `:data` type —
    // `ItemEntityMapper` appears only in constructors of classes consumers never build themselves.
    implementation(projects.data)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.documentfile)
    // Transmuxing only: an extra audio track has to be fetched through the video endpoint, so the junk
    // video is stripped locally. Nothing here decodes or re-encodes, so no codec dependency is needed.
    implementation(libs.androidx.media3.transformer)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
    ksp(libs.androidx.hilt.compiler)
}
