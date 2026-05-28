plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unoone.agent.phonecontrol"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        targetSdk = 34
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    // World-class on-device OCR
    implementation("com.google.mlkit:text-recognition:16.0.0")
    
    // World-class offline on-device Object Detection and Tracking
    implementation("com.google.mlkit:object-detection:17.0.2")
}
