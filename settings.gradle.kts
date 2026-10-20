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
        // Injekt (DI) is used by the vendored Aniyomi source-api and is only published here.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Animarvle"
include(":app")
// ARCH §2 module layout. :source-api / :domain / :source-sample are built as
// Android libraries so they reuse AGP 9.4's built-in Kotlin support; they contain
// no Android APIs (:domain must stay Android-free, ARCH §2).
include(":source-api")
include(":domain")
include(":source-sample")
// Vendored Aniyomi source-api interfaces (Apache-2.0) for hosting extension APKs.
include(":aniyomi-api")
