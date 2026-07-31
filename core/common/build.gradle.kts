plugins {
    alias(libs.plugins.jellyboost.kotlin.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // `api` rather than `implementation`: `SyncPlaySession.activeGroup` is a `StateFlow`, so
    // coroutines are part of this module's own signature and every consumer compiles against them
    // (M11 Phase 4).
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}

// The quality gate (`/verify`) runs `testDebugUnitTest`, a task that only exists on Android
// modules — without this alias the tests in this pure-JVM module would silently never run.
tasks.register("testDebugUnitTest") {
    description = "Alias for `test`, so this module joins the project-wide unit-test gate."
    group = "verification"
    dependsOn(tasks.named("test"))
}
