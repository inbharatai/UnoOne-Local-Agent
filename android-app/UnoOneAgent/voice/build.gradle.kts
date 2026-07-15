plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unoone.agent.voice"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":observability"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---------------------------------------------------------------------------
    // Sherpa-ONNX speech runtime — production offline STT / TTS / keyword spotting.
    //
    // PRIMARY (Maven, auto-resolving): JitPack builds the sherpa-onnx Android AAR
    // (JNI .so for arm64-v8a / armeabi-v7a / x86 / x86_64) from the k2-fsa source.
    // FALLBACK (local AAR): drop the pre-built AAR into voice/libs/ and the fileTree
    // below picks it up. Download from
    //   https://github.com/k2-fsa/sherpa-onnx/releases
    //   → sherpa-onnx-v1.13.3-android.tar.bz2 → extract → sherpa-onnx.aar → voice/libs/
    //
    // The engines load these classes defensively, so the app compiles and runs whether
    // or not a native AAR is present; with an AAR present they use real on-device
    // inference instead of the emergency Android STT/TTS fallback.
    // ---------------------------------------------------------------------------
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")

    testImplementation("junit:junit:4.13.2")
}
