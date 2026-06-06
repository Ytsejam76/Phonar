import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ytsejam.phonar"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.ytsejam.phonar"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

abstract class BuildRustTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputDirectory
    abstract val rustDir: DirectoryProperty

    @get:InputDirectory
    abstract val ndkDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @TaskAction
    fun build() {
        val outputDirectory = outputDir.get().asFile
        outputDirectory.mkdirs()

        execOperations.exec {
            workingDir = rustDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            commandLine(
                "cargo",
                "ndk",
                "--platform",
                "26",
                "-t",
                "arm64-v8a",
                "-t",
                "armeabi-v7a",
                "-t",
                "x86_64",
                "-o",
                outputDirectory.absolutePath,
                "build",
                "--release",
            )
        }
    }
}

val buildRust by tasks.registering(BuildRustTask::class) {
    outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
    rustDir.set(layout.projectDirectory.dir("src/main/rust"))
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    rustInputs.from(
        layout.projectDirectory.file("src/main/rust/Cargo.toml"),
        layout.projectDirectory.file("src/main/rust/Cargo.lock"),
        layout.projectDirectory.dir("src/main/rust/src"),
    )
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRust) { it.outputDir }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
