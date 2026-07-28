import androidx.room.gradle.RoomExtension
import com.google.devtools.ksp.gradle.KspExtension
import dev.jellyfinnative.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Convention plugin adding Room (runtime + KSP compiler + schema export) to an Android module. */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("androidx.room")
            }

            extensions.configure<KspExtension> {
                arg("room.generateKotlin", "true")
            }

            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }

            dependencies {
                add("implementation", libs.findLibrary("androidx-room-runtime").get())
                add("implementation", libs.findLibrary("androidx-room-ktx").get())
                add("implementation", libs.findLibrary("androidx-room-paging").get())
                add("ksp", libs.findLibrary("androidx-room-compiler").get())
            }
        }
    }
}
