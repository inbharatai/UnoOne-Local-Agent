plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unoone.agent.localbrain"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    sourceSets.getByName("main").resources.srcDir("src/main/cpp/licenses")

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("src/main/cpp/qwen-consumer-rules.pro", "src/main/cpp/owl-consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                targets += listOf("unoone_qwen", "unoone_owl", "mtmd", "llama", "ggml", "ggml-base", "ggml-cpu")
            }
        }
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

    // LiteRT-LM for Gemma 4 E4B on-device inference with manual, safety-gated tool calling.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12")
}
