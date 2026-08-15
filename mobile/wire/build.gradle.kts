plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}

android {
    namespace = "ru.bolid.testdpls.wire"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
}
