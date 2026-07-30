@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jellyfin-native"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
// Instrumented macrobenchmark that records the release baseline profile (M10). Produces no
// shipped code; its generation task is device-only — see baselineprofile/build.gradle.kts.
include(":baselineprofile")
include(":core:common")
include(":core:ui")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":data")
include(":data:downloads")
include(":player")
include(":feature:auth")
include(":feature:home")
include(":feature:library")
include(":feature:detail")
include(":feature:search")
include(":feature:downloads")
include(":feature:settings")
