plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unoone.agent.localbrain"
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
    implementation(project(":observability"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // LiteRT-LM for Gemma 4 E2B on-device inference with manual tool calling
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12")
}
