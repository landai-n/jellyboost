import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Baseline profile generator (M10, docs/PLAN.md "Release hardening").
 *
 * A `com.android.test` module: it ships no code, only an instrumented macrobenchmark that drives
 * `:app` through its startup and scrolling flows while ART records which methods and classes were
 * used. The recording is written back into `app/src/main/generated/baselineProfiles/` and
 * packaged into the APK, where `androidx.profileinstaller` hands it to the platform so those paths
 * are AOT-compiled on first run instead of being interpreted and JIT-compiled.
 *
 * **Generation requires a device** and is therefore *not* part of the normal build or of `/verify`:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * `automaticGenerationDuringBuild = false` in `:app` is what keeps `assembleDebug` and
 * `assembleRelease` device-free. Until the task has been run the generated directory does not
 * exist and the release build packages no profile — a missed optimisation, never a build failure.
 *
 * This module deliberately sits outside the project's conventions: macrobenchmark is a JUnit 4 API
 * (the rest of the project is JUnit 5) and none of the convention plugins fit a `com.android.test`
 * module, so AGP is configured directly here.
 */
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Read straight from gradle/libs.versions.toml so this module can never drift from the app it
// profiles. (`Integer.parseInt` rather than `.toInt()` only to keep the call chains flat enough
// for ktlint's chain-method-continuation rule.)
val catalogCompileSdk: Int = Integer.parseInt(libs.versions.androidCompileSdk.get())
val catalogCompileSdkMinor: Int = Integer.parseInt(libs.versions.androidCompileSdkMinor.get())
val catalogTargetSdk: Int = Integer.parseInt(libs.versions.androidTargetSdk.get())
val kotlinJvmTarget: String = libs.versions.jvmTarget.get()

android {
    namespace = "dev.jellyfinnative.baselineprofile"
    compileSdk = catalogCompileSdk
    compileSdkMinor = catalogCompileSdkMinor

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Macrobenchmark needs API 28+ to pull the recorded profile back off the device. That is
        // above the app's own minSdk 26; the generated profile still applies from minSdk upwards.
        minSdk = 28
        targetSdk = catalogTargetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Generation runs against a real connected device — the test tablet test tablet used for every
    // milestone DoD — rather than a Gradle-managed AVD the project does not define.
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
