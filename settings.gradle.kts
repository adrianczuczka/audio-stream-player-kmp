rootProject.name = "audio-stream-player"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":audio-stream-player")
include(":smoke-test")
include(":smoke-ios")
