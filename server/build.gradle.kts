plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.cio)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.slf4j.nop)
}

tasks.test {
    systemProperty("busys8.vendor", rootProject.file("vendor").absolutePath)
}
