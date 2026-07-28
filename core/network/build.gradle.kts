plugins {
    alias(libs.plugins.jellyfinnative.android.library)
    alias(libs.plugins.jellyfinnative.android.hilt)
}

android {
    namespace = "dev.jellyfinnative.core.network"
}

dependencies {
    api(projects.core.common)
    api(libs.jellyfin.sdk)
    api(libs.okhttp)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // jellyfin-sdk logs through kotlin-logging, which resolves to SLF4J on Android; without a
    // binding on the runtime classpath every SDK logger construction throws NoClassDefFoundError
    // (it took down UDP server discovery). See DECISIONS.md, 2026-07-28.
    runtimeOnly(libs.slf4j.android)
}
