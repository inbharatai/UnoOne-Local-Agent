plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.unoone.agent.securebrowser"
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
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation("junit:junit:4.13.2")
}

// Fail closed: local builds must never silently package a missing or obsolete privileged runtime.
val verifyBrowserRuntime by tasks.registering {
    val asset = layout.projectDirectory.file("src/main/assets/page-agent/unoone-page-agent.js")
    inputs.file(asset)
    doLast {
        val file = asset.asFile
        check(file.isFile && file.length() > 0) {
            "Missing DOM runtime. Run npm run bundle:android in web-runtime/page-agent-unoone."
        }
        val source = file.readText()
        check(source.contains("UnoOneDomAdapter") && !source.contains("__UNOONE_PAGE_AGENT_SESSION__")) {
            "Obsolete privileged browser runtime: rebuild npm run bundle:android."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyBrowserRuntime) }
