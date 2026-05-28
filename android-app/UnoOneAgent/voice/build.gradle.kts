plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unoone.agent.voice"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    // Decoupling Sherpa compile dependency so the app successfully compiles universally on any setup!
    // Since we used reflection inside SherpaSttEngine, SherpaTtsEngine, and KeywordSpotterEngine,
    // we don't need compileOnly or implementation dependencies on Sherpa-ONNX.
    // If the user adds the .aar file to their local project/libs directory or the Maven repo resolves, 
    // it will load and run immediately. Otherwise, the robust native fallback is used.
}
