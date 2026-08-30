plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// :agent exposes this module through `api`, so it has to be resolvable for anyone
// consuming the agent from a Maven repository.
// The repository this publishes to is no longer named here. It used to be read from `REPOSILITE_URL`
// with the note that "a public build file is a poor place to publish the location of a private Maven
// repository" — and the address is now in the public source of `ru.workinprogress.sborka`, together
// with the six repositories that have already migrated, so keeping it out of THIS file conceals
// nothing that is still concealed. `sborka.publish` declares it, and `sborka.snapshotRepository`
// overrides it if that ever needs to change.

kotlin {
    withSourcesJar()

    jvm()

    macosArm64()
    linuxX64()
    linuxArm64()

    // Apple mobile. The agent is a library that ends up inside somebody else's client, and an
    // iOS build had nowhere to send a record at all (issue #16).
    iosArm64()
    iosSimulatorArm64()
    iosX64()

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
