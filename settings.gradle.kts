pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Compose Multiplatform 1.12 resolves its runtime from androidx artifacts,
        // which are published to Google's Maven repository only.
        google()
        mavenCentral()
    }
}

rootProject.name = "camusic-linux"
// CAMusic for Linux — the Compose Desktop panel for the NUC touchscreen.
include(":linux")
