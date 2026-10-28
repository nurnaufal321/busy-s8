plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    implementation(libs.quickjs)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    systemProperty("busys8.vendor", rootProject.file("vendor").absolutePath)
}
