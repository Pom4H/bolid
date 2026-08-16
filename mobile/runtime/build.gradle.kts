plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":wire"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "ru.bolid.testdpls.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
}
