plugins {
    kotlin("multiplatform")
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "wip"
            url = uri("https://reposilite.kotlin.website/snapshots")
            credentials {
                username = findProperty("REPOSILITE_USER")?.toString()
                password = findProperty("REPOSILITE_SECRET")?.toString()
            }
        }
    }
}

kotlin {
    withSourcesJar()

    jvm()
    jvmToolchain(21)

    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.callId)
            implementation(libs.kotlin.logging)
            // The client is shared, the engine is per-platform: CIO has no TLS on
            // Kotlin/Native and drags a SelectorManager into the host process.
            implementation(ktorLibs.client.core)
        }
        jvmMain.dependencies {
            implementation(ktorLibs.client.cio)
        }
        nativeMain.dependencies {
            implementation(ktorLibs.client.curl)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
