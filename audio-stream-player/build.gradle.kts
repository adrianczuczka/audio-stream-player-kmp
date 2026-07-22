import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

group = "com.adrianczuczka"
version = "0.1.0"

kotlin {
    explicitApi()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.adrianczuczka.audiostreamplayer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTest {}
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.adrianczuczka", "audio-stream-player", version.toString())

    pom {
        name.set("audio-stream-player")
        description.set(
            "Low-latency raw PCM streaming audio player for Kotlin Multiplatform " +
                "(Android, iOS, macOS). Feed audio chunks as they arrive from a TTS " +
                "or realtime voice API."
        )
        url.set("https://github.com/adrianczuczka/audio-stream-player-kmp")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("adrianczuczka")
                name.set("Adrian Czuczka")
            }
        }
        scm {
            url.set("https://github.com/adrianczuczka/audio-stream-player-kmp")
        }
    }
}
