plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.serialization.json)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("busys8.vendor", rootProject.file("vendor").absolutePath)
    systemProperty("busys8.upstream", rootProject.file("upstream/src").absolutePath)
}
