plugins {
    alias(libs.plugins.jellyboost.kotlin.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // `api` rather than `implementation`: `SyncPlaySession.activeGroup` is a `StateFlow`, so
    // coroutines are part of this module's own signature and every consumer compiles against them.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // `api`, not `implementation`: `di/` holds the project's four DI qualifiers and
    // `@Qualifier` is part of their declaration, so every module that annotates an injection site
    // compiles against it. JSR-330 annotations only — no Dagger, no processor, no Android; this
    // module stays pure JVM and the `@Provides` bindings stay in `:core:network`.
    api(libs.javax.inject)
}

// The quality gate (`/verify`) runs `testDebugUnitTest`, a task that only exists on Android
// modules — without this alias the tests in this pure-JVM module would silently never run.
tasks.register("testDebugUnitTest") {
    description = "Alias for `test`, so this module joins the project-wide unit-test gate."
    group = "verification"
    dependsOn(tasks.named("test"))
}
