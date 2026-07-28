package dev.jellyfinnative.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

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
}
