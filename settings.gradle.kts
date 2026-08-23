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
    }
}

rootProject.name = "homecam"

// :core is pure JVM and must stay free of Android dependencies — it holds the wire
// protocol, crypto and state machines so they are testable without an emulator.
include(":core")
include(":app")
