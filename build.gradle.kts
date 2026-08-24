import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Applied only by :baselineprofile; declared here so AGP resolves once for the whole build.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.baselineprofile) apply false
}

allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // `uk.uuid.slf4j:slf4j-android` binds SLF4J to `android.util.Log`, a stub in local unit tests:
    // the binding fails to initialise and takes MockK's own SLF4J logger down with it.
    configurations.matching { it.name.contains("UnitTestRuntimeClasspath") }.configureEach {
        exclude(group = "uk.uuid.slf4j", module = "slf4j-android")
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        ignoreFailures = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
    }

    extensions.configure<KtlintExtension> {
        // Code style comes from .editorconfig (ktlint_official).
        ignoreFailures.set(false)
        reporters {
            reporter(ReporterType.PLAIN)
        }
        filter {
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = libs.versions.jvmTarget.get()
        reports {
            html.required.set(true)
            sarif.required.set(true)
            md.required.set(false)
            txt.required.set(false)
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
