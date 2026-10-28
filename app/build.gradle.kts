plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.busys8"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.busys8"
        minSdk = 28
        //noinspection ExpiredTargetSdkVersion: the S8+ runs Android 9 and never sees the Play Store
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].assets.srcDir(rootProject.file("vendor"))
    // SoundPool reads WAVs through file descriptors, which needs them stored uncompressed.
    // busy-matter (libbusymatter.so) is a program, run from the extracted native library dir.
    packaging { jniLibs { useLegacyPackaging = true } }

    androidResources {
        noCompress += "wav"
        // The default pattern drops directories starting with "_", which removes the web UI's _nuxt/.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":server"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.slf4j.nop)
    implementation(libs.jmdns)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    testImplementation(libs.junit)
}
