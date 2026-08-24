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
 * AGP 9's [CommonExtension] exposes plain getters rather than the `defaultConfig { }` style blocks
 * (those exist only on the concrete application/library extensions), hence the property access below.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    with(commonExtension) {
        compileSdk = libs.intVersion("androidCompileSdk")
        compileSdkMinor = libs.intVersion("androidCompileSdkMinor")
        defaultConfig.minSdk = libs.intVersion("androidMinSdk")
        // Every module, not only `:app`: a library with no runner declared has no
        // `connectedDebugAndroidTest` to run, and the a11y suite lives beside its components.
        defaultConfig.testInstrumentationRunner = ANDROID_JUNIT_RUNNER

        with(compileOptions) {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = true
        }

        with(lint) {
            // Lint is a gate, not a report: severities live in `config/lint/lint.xml` alone.
            abortOnError = true
            // One config for every module; without it a module picks up its own `lint.xml`, which is
            // how a per-module exemption gets added by accident.
            lintConfig = rootProject.file(LINT_CONFIG_PATH)
            // Why the gate can run as `:app:lintDebug` alone: the app's run analyses every library it
            // depends on, one pass instead of seventeen.
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

/** Relative to the root project. */
private const val LINT_CONFIG_PATH = "config/lint/lint.xml"

/** JUnit 4, because the on-device instrumentation runner has no JUnit Platform to launch. */
private const val ANDROID_JUNIT_RUNNER = "androidx.test.runner.AndroidJUnitRunner"

internal fun Project.configureKotlinJvmTarget() {
    val target = JvmTarget.fromTarget(libs.version("jvmTarget"))
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(target)
        }
    }
}
