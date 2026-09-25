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
        google()
        mavenCentral()
    }
}

rootProject.name = "sendpin"
include(":app")
// Generates the startup/scroll baseline profile. Never built by a normal assemble —
// see baselineprofile/build.gradle.kts for how to run it.
include(":baselineprofile")
// CAMusic for Linux — Compose Desktop panel for the NUC touchscreen. Local
// testing only; shares source with :app (see linux/build.gradle.kts).
include(":linux")
