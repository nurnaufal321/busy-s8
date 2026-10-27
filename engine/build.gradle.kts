plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("busys8.vendor", rootProject.file("vendor").absolutePath)
}
