plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.unoone.agent.modelmanager"
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
            // android.util.Log calls (via Logger) return defaults instead of throwing "not mocked"
            // during pure-JVM unit tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":storage"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // Archive extraction beyond ZIP: tar.bz2 / tar.gz for model tarballs (e.g. the public
    // sherpa-onnx-whisper-tiny.tar.bz2). Android's stdlib only ships java.util.zip; commons-compress
    // is pure-Java, Android-safe (~1 MB) and lets the installer point a manifest entry at a public
    // tarball instead of re-hosting its contents. ZIP archives still use java.util.zip directly.
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
}
