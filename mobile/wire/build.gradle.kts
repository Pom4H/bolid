import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}

android {
    namespace = "ru.bolid.testdpls.wire"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
}
