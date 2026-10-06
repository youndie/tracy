pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // The Android Gradle plugin is published here and nowhere else (M-145: the agent has an Android
        // target). Filtered: an unfiltered repository takes part in resolving every plugin. Three
        // groups, because the plugin's own classpath reaches into androidx and com.google.
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.google")
            }
        }
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // One group, and it is the only one there can be. The portfolio's move to
                // `io.github.youndie` is finished: nothing this build resolves is under
                // `ru.workinprogress` any more, and a filter naming a group the server is never asked
                // about reads as a dependency that is still there.
                includeGroupByRegex("io\\.github\\.youndie.*")
            }
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
    id("io.github.youndie.sborka.settings") version "0.4.0.111"
}

dependencyResolutionManagement {
    // The Kotlin plugin registers its own repositories for the Node and Yarn the wasmJs tests run on
    // (M-145). Settings repositories win over project ones in this portfolio, so without these two
    // the lookup falls through to Maven Central and fails with "Could not find org.nodejs:node".
    // Filtered to the one module each serves: an unfiltered repository takes part in resolving
    // every dependency.
    repositories {
        ivy("https://nodejs.org/dist/") {
            name = "Node distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
    versionCatalogs {
        create("ktorLibs") {
            from("io.ktor:ktor-version-catalog:3.6.0")
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "tracy"

include(":shared")
include(":agent")
include(":agent-ktor-server")
include(":server")
