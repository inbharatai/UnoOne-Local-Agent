pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // The correct maven repo for sherpa-onnx
        maven { url = uri("https://k2-fsa.github.io/sherpa/onnx/android/") }
    }
}

rootProject.name = "UnoOneAgent"

include(
    ":app",
    ":core",
    ":storage",
    ":modelmanager",
    ":localbrain",
    ":voice",
    ":agentrouter",
    ":safetyguard",
    ":phonecontrol",
    ":memory",
    ":skills",
    ":observability",
    ":accessibilitycontrol"
)
