package dev.jellyboost.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Enables Jetpack Compose and wires the Compose BOM plus the shared Compose dependency set. */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

    commonExtension.buildFeatures.compose = true

    dependencies {
        val bom = platform(libs.findLibrary("androidx-compose-bom").get())
        add("implementation", bom)
        add("implementation", libs.findBundle("compose").get())
        add("androidTestImplementation", bom)
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
    }

    configureComposeCompilerMetrics()
}

/**
 * Report-only Compose compiler metrics/reports, off by default (audit PERF-11).
 *
 * There was no way to see which composables were stable/skippable without hand-instrumenting one at
 * a time; the compiler already computes exactly that on every compile and can be asked to write it
 * out. Gated behind a Gradle property rather than always-on: the reports are a build-time cost
 * (extra compiler passes and file I/O) nobody wants paying on every debug build, and they are
 * genuinely report-only — nothing here changes what ships. Opt in per-build with:
 * `./gradlew assembleDebug -Pjellyboost.composeCompilerMetrics=true`, output lands under each
 * module's `build/compose-metrics` and `build/compose-reports`.
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
