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
    // The SDK types appear in this module's own (internal) API surface — repositories and mappers.
    api(libs.jellyfin.sdk)
    // `PagingData` appears in JellyfinRepository's signature (M3 library grid), so it is api.
    api(libs.androidx.paging.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.androidx.paging.testing)
    // Walks `JellyfinRepository`'s members so a new one cannot be forgotten by the delegate
    // (audit ARCH-09, `DelegatingJellyfinRepositoryTest`). Test-only, by design.
    testImplementation(libs.kotlin.reflect)
}
