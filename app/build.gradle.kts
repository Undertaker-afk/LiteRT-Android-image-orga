plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.imageorga.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imageorga.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // LiteRT-LM ships native libraries for these two ABIs only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
        jniLibs {
            // The NPU runtimes are loaded by file path from the app's native library
            // directory, so the libraries must be unpacked at install time.
            useLegacyPackaging = true
            // Parts of Qualcomm's runtime this app never uses: the old DSP and GPU
            // backends and NPU generations older than the chips that have model builds.
            excludes += setOf(
                "**/libQnnDsp*.so",
                "**/libQnnGpu.so",
                "**/libQnnHtpV68*.so",
                "**/libQnnHtpV69*.so",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // On-device embedding runtime (LiteRT-LM) used to run EmbeddingGemma 2.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    // NPU support. The first three connect LiteRT to each vendor's NPU; Tensor and
    // MediaTek phones ship the rest of the runtime in the system image, Qualcomm's
    // has to be bundled with the app.
    implementation("com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.2")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.2")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.2")
    implementation("com.qualcomm.qti:qnn-runtime:2.51.0")

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
