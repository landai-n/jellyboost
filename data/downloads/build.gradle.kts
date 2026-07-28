plugins {
    alias(libs.plugins.jellyfinnative.android.library)
    alias(libs.plugins.jellyfinnative.android.hilt)
}

android {
    namespace = "dev.jellyfinnative.data.downloads"
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    implementation(projects.data)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.hilt.compiler)
}
