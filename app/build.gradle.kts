plugins {
    alias(libs.plugins.jellyfinnative.android.application)
    alias(libs.plugins.jellyfinnative.android.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.jellyfinnative.app"

    defaultConfig {
        applicationId = "dev.jellyfinnative.app"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            // R8 / shrinking is wired up in M10 (release hardening).
            isMinifyEnabled = false
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

    ksp(libs.androidx.hilt.compiler)
}
