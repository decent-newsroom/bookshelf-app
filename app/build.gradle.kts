import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

abstract class CheckReleaseKeystoreTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val keystorePropertiesFile: RegularFileProperty

    @TaskAction
    fun check() {
        require(keystorePropertiesFile.asFile.get().isFile) {
            "Missing keystore.properties. Copy keystore.properties.example, fill it in, then rerun assembleRelease."
        }
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("0.1.0")
val appVersionCode =
    providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()?.also { versionCode ->
        require(versionCode in 1..2_100_000_000) {
            "appVersionCode must be an integer between 1 and 2100000000."
        }
    } ?: if (providers.gradleProperty("appVersionCode").isPresent) {
        error("appVersionCode must be an integer between 1 and 2100000000.")
    } else {
        1
    }

require(Regex("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)").matches(appVersionName)) {
    "appVersionName must use MAJOR.MINOR.PATCH without a leading v."
}

android {
    namespace = "eu.decentnewsroom.bookshelf"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.decentnewsroom.bookshelf"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

tasks.register<CheckReleaseKeystoreTask>("checkReleaseKeystore") {
    description = "Check release keystore"
    keystorePropertiesFile.set(rootProject.layout.projectDirectory.file("keystore.properties"))
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn("checkReleaseKeystore")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.asciidoc.parser)
    implementation(libs.asciidoc.html.renderer)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.quartz)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
