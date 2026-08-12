plugins {
    kotlin("multiplatform")
    alias(libs.plugins.pluginSerialization)
    `maven-publish`
}

// :agent exposes this module through `api`, so it has to be resolvable for anyone
// consuming the agent from a Maven repository.
publishing {
    repositories {
        // Address, user and secret all come from outside the repository. The URL is not a
        // credential, but it is infrastructure, and a public build file is a poor place to
        // publish the location of a private Maven repository.
        val repositoryUrl = providers.gradleProperty("REPOSILITE_URL").orElse(providers.environmentVariable("REPOSILITE_URL"))
        if (repositoryUrl.isPresent) {
            maven {
                name = "wip"
                url = uri(repositoryUrl.get())
                credentials {
                    username = providers.gradleProperty("REPOSILITE_USER").orElse(providers.environmentVariable("REPOSILITE_USER")).orNull
                    password =
                        providers.gradleProperty("REPOSILITE_SECRET").orElse(providers.environmentVariable("REPOSILITE_SECRET")).orNull
                }
            }
        }
    }
}

kotlin {
    withSourcesJar()

    jvm()
    jvmToolchain(25)

    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            // API paths are declared here, typed: both sides take the same contract.
            api(ktorLibs.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
