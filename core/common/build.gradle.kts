plugins {
    alias(libs.plugins.jellyfinnative.kotlin.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}

// The quality gate (`/verify`) runs `testDebugUnitTest`, a task that only exists on Android
// modules — without this alias the tests in this pure-JVM module would silently never run.
tasks.register("testDebugUnitTest") {
    description = "Alias for `test`, so this module joins the project-wide unit-test gate."
    group = "verification"
    dependsOn(tasks.named("test"))
}
