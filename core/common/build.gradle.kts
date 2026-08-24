plugins {
    alias(libs.plugins.jellyboost.kotlin.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // `api`: `SyncPlaySession.activeGroup` is a `StateFlow`, so coroutines are part of this module's signature.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // `api`: `@Qualifier` is part of the DI qualifiers' declaration, so every module annotating an injection
    // site compiles against it. JSR-330 only — no Dagger, no processor, no Android; this module stays pure JVM.
    api(libs.javax.inject)
}

// The quality gate (`/verify`) runs `testDebugUnitTest`, a task that only exists on Android modules — without
// this alias the tests in this pure-JVM module would silently never run.
tasks.register("testDebugUnitTest") {
    description = "Alias for `test`, so this module joins the project-wide unit-test gate."
    group = "verification"
    dependsOn(tasks.named("test"))
}
