plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":wire"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("ru.bolid.testdpls.interop.ZmuInteropKt")
}
