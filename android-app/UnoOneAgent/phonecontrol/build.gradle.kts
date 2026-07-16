plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unoone.agent.phonecontrol"
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
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    
    // On-device OCR via bundled ML Kit text-recognition.
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Labeled, fully offline COCO object detection for Blind Aid.
    //noinspection GradleDependency -- Google publishes a legacy date-version that sorts above
    // the current 0.10.x line; use the current Tasks release instead.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // CameraX for real-time continuous blind aid analysis
    val cameraVersion = "1.3.3"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
}
