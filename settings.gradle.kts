pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

// Lets Gradle fetch the JDK the toolchain asks for instead of demanding it be installed first.
// Without this, `jvmToolchain(25)` builds only on a machine where someone already put a JDK 25 —
// which is the developer box today and neither the CI runner nor the build image tomorrow.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // The repositories with their content filters, the shared `wip` catalog, and the check that this
    // repository's `.editorconfig` is the one the rest of them use — this one had no `.editorconfig`
    // at all, so ktlint was reading its own defaults.
    id("ru.workinprogress.sborka.settings") version "0.1.0.23"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("ktorLibs") {
            from("io.ktor:ktor-version-catalog:3.5.2")
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "tracy"

include(":shared")
include(":agent")
include(":server")
