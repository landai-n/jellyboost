import java.util.Properties

plugins {
    alias(libs.plugins.jellyfinnative.android.application)
    alias(libs.plugins.jellyfinnative.android.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

/**
 * Release signing material, read from `local.properties` first and the environment second.
 *
 * Nothing secret ever lives in the repository: `local.properties` is gitignored and no keystore is
 * generated into the tree. CI supplies the same four values as environment variables. When none of
 * them is present — which is the normal case for a local `assembleRelease` and for the CI job,
 * neither of which has the release key — the release variant falls back to the debug key and is
 * marked as such in its version name (see `release` below), so an unsigned-for-distribution build
 * can never be mistaken for a real one.
 */
val releaseSigningKeys =
    listOf(
        "RELEASE_STORE_FILE",
        "RELEASE_STORE_PASSWORD",
        "RELEASE_KEY_ALIAS",
        "RELEASE_KEY_PASSWORD",
    )

val releaseSigning: Map<String, String>? =
    run {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            val stream = localPropertiesFile.inputStream()
            stream.use(localProperties::load)
        }

        fun value(key: String): String? {
            val fromFile = localProperties.getProperty(key)
            val raw = if (fromFile.isNullOrBlank()) System.getenv(key) else fromFile
            return if (raw.isNullOrBlank()) null else raw
        }

        val resolved = releaseSigningKeys.associateWith(::value)
        // All four or none: a half-configured keystore would fail late, inside the signing task,
        // with a message that says nothing about which value is missing.
        if (resolved.values.any { it == null }) null else resolved.mapValues { it.value!! }
    }

android {
    namespace = "dev.jellyfinnative.app"

    defaultConfig {
        applicationId = "dev.jellyfinnative.app"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("RELEASE_STORE_FILE"))
                storePassword = releaseSigning.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("RELEASE_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            // R8 with the default full mode. The keep rules live in `proguard-rules.pro`, which
            // documents why each one exists and — as importantly — which libraries already ship
            // their own consumer rules and therefore need nothing from us (M10, docs/PLAN.md).
            isMinifyEnabled = true
            // Safe because the app has no resource lookup by name (no getIdentifier() anywhere).
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (releaseSigning != null) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            // A debug-signed release build is a development artefact: installable on the test
            // tablet for minified-build verification and profiling, never distributable. Marking
            // the version name is what keeps the two apart at a glance in Settings > Apps.
            if (releaseSigning == null) {
                versionNameSuffix = "-debugsigned"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * Consumer side of baseline profile generation (M10). The producer is `:baselineprofile`.
 *
 * Nothing here touches a device: generation is an explicit, device-only task run in a device
 * session (`./gradlew :app:generateBaselineProfile`). Until it has been run,
 * `app/src/main/generated/baselineProfiles/` does not exist and the release build simply
 * packages no profile — `assembleDebug` and `assembleRelease` stay green either way.
 */
baselineProfile {
    // Never generate as a side effect of assembling; that would make every release build require a
    // connected device, including CI's.
    automaticGenerationDuringBuild = false
    // Write the recording into the source tree so it is reviewed, checked in, and available to
    // machines with no device (CI).
    saveInSrc = true
    // One profile for the whole app rather than a file per flow — the release variant is the only
    // consumer and a single file keeps the diff reviewable.
    mergeIntoMain = true
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.data)
    implementation(projects.data.downloads)
    implementation(projects.player)
    implementation(projects.feature.auth)
    implementation(projects.feature.home)
    implementation(projects.feature.library)
    implementation(projects.feature.detail)
    implementation(projects.feature.search)
    implementation(projects.feature.downloads)
    implementation(projects.feature.settings)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    // Icons.Filled.VideoLibrary (the Libraries tab) lives only in the extended icon set, not in
    // the material3 core the `compose` bundle already pulls in.
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // Installs the packaged baseline profile into ART on first run. Already arrives transitively
    // via Compose, but the profile is useless without it, so the dependency is declared where the
    // reason for it lives.
    implementation(libs.androidx.profileinstaller)
    // Points the baseline-profile plugin at the generator module; it is what creates
    // `:app:generateBaselineProfile` and what copies the recording into the release variant.
    baselineProfile(projects.baselineprofile)

    ksp(libs.androidx.hilt.compiler)
}
