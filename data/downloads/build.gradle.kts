plugins {
    alias(libs.plugins.jellyfinnative.android.library)
    alias(libs.plugins.jellyfinnative.android.hilt)
}

android {
    namespace = "dev.jellyfinnative.data.downloads"

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
    // `implementation`, not `api` (audit ARCH-04). The old comment justified `api` with
    // "`DownloadItem` carries a `JellyfinItem`, and the feature modules that render it must see the
    // type" — but `JellyfinItem` lives in `:core:common`, which is api'd two lines up. Nothing in
    // this module's *public* surface names a `:data` type: `ItemEntityMapper` appears only in
    // constructors of classes consumers never build themselves.
    implementation(projects.data)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
    ksp(libs.androidx.hilt.compiler)
}
