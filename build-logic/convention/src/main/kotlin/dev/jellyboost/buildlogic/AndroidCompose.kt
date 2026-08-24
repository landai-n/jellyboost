package dev.jellyboost.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

    commonExtension.buildFeatures.compose = true

    dependencies {
        val bom = platform(libs.findLibrary("androidx-compose-bom").get())
        add("implementation", bom)
        add("implementation", libs.findBundle("compose").get())
        add("androidTestImplementation", bom)
        add("androidTestImplementation", libs.findBundle("compose-ui-test").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        // Debug, not androidTest: this contributes the bare `ComponentActivity`
        // `createAndroidComposeRule` launches, and its manifest entry must be in the app under test.
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
    }

    configureComposeCompilerMetrics()
}

/**
 * Report-only stability/skippability metrics, off by default because they cost extra compiler passes.
 * Opt in with `./gradlew assembleDebug -Pjellyboost.composeCompilerMetrics=true`; output lands under
 * each module's `build/compose-metrics` and `build/compose-reports`.
 */
private fun Project.configureComposeCompilerMetrics() {
    val enabled =
        providers.gradleProperty(COMPOSE_COMPILER_METRICS_PROPERTY).getOrElse("false").toBoolean()
    if (!enabled) return

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
    }
}

private const val COMPOSE_COMPILER_METRICS_PROPERTY = "jellyboost.composeCompilerMetrics"
