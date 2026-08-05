package dev.jellyboost.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Applies the compile SDK / min SDK / Java + Kotlin target settings shared by every Android
 * module in the project.
 *
 * Note: AGP 9's [CommonExtension] exposes plain getters rather than the `defaultConfig { }`
 * style blocks (those only exist on the concrete application/library extensions), hence the
 * property access below.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    with(commonExtension) {
        compileSdk = libs.intVersion("androidCompileSdk")
        compileSdkMinor = libs.intVersion("androidCompileSdkMinor")
        defaultConfig.minSdk = libs.intVersion("androidMinSdk")

        with(compileOptions) {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = true
        }

        with(lint) {
            // Lint is a build gate, not a report: an issue at `error` severity stops the build.
            // Which issues those are is decided in one place — `config/lint/lint.xml` — where the
            // accessibility checks are errors and the families this project has never enforced are
            // demoted to warnings, so turning the switch on could not fail anything that was
            // passing (accessibility audit 2026-08-05, CR-7 / A11Y-LINT-01).
            abortOnError = true
            // One config for every module. Without this each module would pick up its own
            // `lint.xml` (or none), which is how a per-module exemption gets added by accident.
            lintConfig = rootProject.file(LINT_CONFIG_PATH)
            // The gate runs as `:app:lintDebug` alone rather than as `lintDebug` across all 17
            // modules: with this on, the app's run analyses every library it depends on and reports
            // their findings too, for one analysis pass instead of seventeen.
            checkDependencies = true
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(libs.intVersion("javaToolchain")))
    }

    configureKotlinJvmTarget()

    dependencies {
        add("coreLibraryDesugaring", libs.findLibrary("desugar-jdk-libs").get())
        add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
        add("testImplementation", libs.findBundle("unit-test").get())
        add("testRuntimeOnly", libs.findLibrary("junit-jupiter-engine").get())
        add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

/** The one lint severity config, shared by every Android module. Relative to the root project. */
private const val LINT_CONFIG_PATH = "config/lint/lint.xml"

/** Pins the Kotlin bytecode target for every Kotlin compilation in this project. */
internal fun Project.configureKotlinJvmTarget() {
    val target = JvmTarget.fromTarget(libs.version("jvmTarget"))
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(target)
        }
    }
}
