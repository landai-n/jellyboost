plugins {
    alias(libs.plugins.jellyboost.android.library)
    alias(libs.plugins.jellyboost.android.hilt)
}

android {
    namespace = "dev.jellyboost.data"
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    // `implementation`, not `api` (audit 2026-08-08, ARCH-1/ARCH-2). The old comment justified `api`
    // with "the SDK types appear in this module's own (internal) API surface", which contradicts
    // itself: an *internal* surface is precisely what must not be exported. Every declaration that
    // named an SDK type and had no cross-module consumer is now `internal`, so nothing a feature
    // module can reach mentions one. This is what makes the plan's "the SDK stops at :data"
    // invariant a *compile* error rather than a detekt rule — detekt's `ForbiddenImport` only sees
    // imports, so a fully-qualified `org.jellyfin.sdk.model.api.BaseItemDto` in a ViewModel used to
    // slip through; now there is no such type on the feature modules' compile classpath at all.
    //
    // The one consumer that genuinely speaks DTOs, `:data:downloads`, is unaffected: it gets the
    // SDK from `:core:network`, which exports it on purpose (`jellyfinAuthorizationHeader` and
    // `Throwable.toAppError` name `ApiClient`/SDK exceptions in their signatures).
    implementation(libs.jellyfin.sdk)
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
