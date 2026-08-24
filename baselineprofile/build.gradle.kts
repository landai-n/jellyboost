import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Baseline profile generator: a `com.android.test` module that ships no code.
 *
 * **Generation requires a device** (`./gradlew :app:generateBaselineProfile`) and is therefore not
 * part of the normal build or of `/verify` — `automaticGenerationDuringBuild = false` in `:app` is
 * what keeps `assemble*` device-free.
 *
 * Deliberately outside the project's conventions: macrobenchmark is a JUnit 4 API where the rest of
 * the project is JUnit 5, and no convention plugin fits a `com.android.test` module.
 */
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Read from the catalog so this module cannot drift from the app it profiles. (`Integer.parseInt`
// rather than `.toInt()` only to keep the chains flat enough for ktlint's continuation rule.)
val catalogCompileSdk: Int = Integer.parseInt(libs.versions.androidCompileSdk.get())
val catalogCompileSdkMinor: Int = Integer.parseInt(libs.versions.androidCompileSdkMinor.get())
val catalogTargetSdk: Int = Integer.parseInt(libs.versions.androidTargetSdk.get())
val kotlinJvmTarget: String = libs.versions.jvmTarget.get()

android {
    namespace = "dev.jellyboost.baselineprofile"
    compileSdk = catalogCompileSdk
    compileSdkMinor = catalogCompileSdkMinor

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Macrobenchmark needs API 28+ to pull the profile back off the device — above the app's own
        // minSdk 26, though the generated profile still applies from minSdk upwards.
        minSdk = 28
        targetSdk = catalogTargetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // A real connected device rather than a Gradle-managed AVD the project does not define.
    useConnectedDevices = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(kotlinJvmTarget))
    }
}

dependencies {
    implementation(libs.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
