import java.util.Properties

plugins {
    alias(libs.plugins.jellyboost.android.application)
    alias(libs.plugins.jellyboost.android.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.aboutlibraries)
}

/**
 * Release signing material, read from `local.properties` (gitignored) first and the environment
 * second. With none of the four present the release variant falls back to the debug key and says so
 * in its version name, so a build that cannot be distributed is never mistaken for one that can.
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
        // All four or none: a half-configured keystore fails late, inside the signing task, with a
        // message that names no missing value.
        if (resolved.values.any { it == null }) null else resolved.mapValues { it.value!! }
    }

android {
    namespace = "dev.jellyboost.app"

    defaultConfig {
        applicationId = "dev.jellyboost.app"
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
            // A debug-signed release build is a development artefact, never distributable; the
            // suffix is what keeps the two apart at a glance in Settings > Apps.
            if (releaseSigning == null) {
                versionNameSuffix = "-debugsigned"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The API 33+ LocaleConfig, generated from the values-* folders; the default locale comes
        // from res/resources.properties (unqualifiedResLocale).
        generateLocaleConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * Repeated from the Compose convention plugin because `:app` applies the Compose plugin directly
 * rather than through that convention — without this the flag would report on every module but this
 * one. Opt in with `-Pjellyboost.composeCompilerMetrics=true`.
 */
if (providers.gradleProperty("jellyboost.composeCompilerMetrics").getOrElse("false").toBoolean()) {
    composeCompiler {
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
    }
}

/**
 * Consumer side of baseline profile generation; the producer is `:baselineprofile`. Nothing here
 * touches a device — until `:app:generateBaselineProfile` has been run the generated directory does
 * not exist and the release build simply packages no profile.
 */
baselineProfile {
    // Never as a side effect of assembling: that would make every release build, CI's included,
    // require a connected device.
    automaticGenerationDuringBuild = false
    // Into the source tree, so the recording is reviewed, checked in, and usable without a device.
    saveInSrc = true
    // One profile rather than a file per flow — the release variant is the only consumer.
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
    implementation(projects.feature.music)
    implementation(projects.feature.search)
    implementation(projects.feature.downloads)
    implementation(projects.feature.settings)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    // Icons.Filled.VideoLibrary (the Libraries tab) is in the extended set only, not in the
    // material3 core the `compose` bundle pulls in.
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Repeated rather than inherited: `:app` applies the Compose plugin directly instead of through
    // the convention that gives every other Compose module these.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.activity.compose)
    // For the cast button alone: MediaRouter's chooser is a DialogFragment (hence
    // `FragmentActivity`) inflating against AppCompat attributes it takes from the activity's theme
    // (hence `Theme.AppCompat.NoActionBar` in themes.xml). No AppCompat *views* enter the app.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
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

    // `JellyboostApplication` names `coil3` types directly and `:core:ui` does not export Coil as
    // `api`.
    implementation(libs.coil.compose)

    // Installs the packaged baseline profile into ART on first run. Arrives transitively via
    // Compose, but the profile is useless without it, so it is declared where the reason lives.
    implementation(libs.androidx.profileinstaller)
    // Creates `:app:generateBaselineProfile` and copies the recording into the release variant.
    baselineProfile(projects.baselineprofile)

    ksp(libs.androidx.hilt.compiler)
}
