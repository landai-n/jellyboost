plugins {
    alias(libs.plugins.jellyfinnative.android.library)
    alias(libs.plugins.jellyfinnative.android.hilt)
}

android {
    namespace = "dev.jellyfinnative.data"
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.hilt.compiler)
}
