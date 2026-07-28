package dev.jellyfinnative.buildlogic

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
            abortOnError = false
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

/** Pins the Kotlin bytecode target for every Kotlin compilation in this project. */
internal fun Project.configureKotlinJvmTarget() {
    val target = JvmTarget.fromTarget(libs.version("jvmTarget"))
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(target)
        }
    }
}
