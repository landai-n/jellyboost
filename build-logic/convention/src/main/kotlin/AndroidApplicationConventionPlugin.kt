import com.android.build.api.dsl.ApplicationExtension
import dev.jellyboost.buildlogic.configureKotlinAndroid
import dev.jellyboost.buildlogic.intVersion
import dev.jellyboost.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig {
                    targetSdk = libs.intVersion("androidTargetSdk")
                    versionCode =
                        target.providers.gradleProperty("jellyboost.versionCode").orNull
                            ?.toIntOrNull()
                            ?: error(
                                "gradle.properties is missing jellyboost.versionCode (or it is not " +
                                    "an integer) — Play rejects an upload without a strictly " +
                                    "increasing versionCode.",
                            )
                    versionName =
                        target.providers.gradleProperty("jellyboost.versionName").orNull
                            ?: error("gradle.properties is missing jellyboost.versionName.")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                buildFeatures {
                    buildConfig = true
                }
            }
        }
    }
}
