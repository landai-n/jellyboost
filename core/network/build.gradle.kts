plugins {
    alias(libs.plugins.jellyboost.android.library)
    alias(libs.plugins.jellyboost.android.hilt)
}

android {
    namespace = "dev.jellyboost.core.network"
}

dependencies {
    api(projects.core.common)
    api(libs.jellyfin.sdk)
    api(libs.okhttp)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // jellyfin-sdk logs through kotlin-logging, which resolves to SLF4J on Android; without a
    // binding on the runtime classpath every SDK logger construction throws NoClassDefFoundError
    // (it would take down UDP server discovery).
    //
    // The binding must therefore stay present in *both* variants — it is what drags
    // `org.slf4j:slf4j-api` in (kotlin-logging-android declares no slf4j dependency of its own).
    // But the SDK logs every request URL at INFO, so the Logcat-writing binding is debug-only and
    // release gets the no-op provider: slf4j-api is still on the classpath and the ServiceLoader
    // lookup still succeeds, while the SDK's search terms / userId / itemIds / server address go
    // nowhere.
    debugRuntimeOnly(libs.slf4j.android)
    releaseRuntimeOnly(libs.slf4j.nop)
}
