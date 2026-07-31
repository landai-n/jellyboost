import com.android.build.api.dsl.ApplicationExtension
import dev.jellyboost.buildlogic.configureKotlinAndroid
import dev.jellyboost.buildlogic.intVersion
import dev.jellyboost.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Convention plugin for the single Android application module. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig {
                    targetSdk = libs.intVersion("androidTargetSdk")
                    versionCode = 1
                    versionName = "0.1.0"
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                buildFeatures {
                    buildConfig = true
                }
            }
        }
    }
}
