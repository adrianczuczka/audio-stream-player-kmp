plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "SmokeShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":audio-stream-player"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
