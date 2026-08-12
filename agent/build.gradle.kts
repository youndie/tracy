plugins {
    kotlin("multiplatform")
    `maven-publish`
}

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
            api(projects.shared)
            implementation(ktorLibs.server.core)
            implementation(libs.kotlin.logging)
            // The client is shared, the engine is per-platform: CIO has no TLS on
            // Kotlin/Native and drags a SelectorManager into the host process.
            implementation(ktorLibs.client.core)
        }
        jvmMain.dependencies {
            implementation(ktorLibs.client.cio)
            // compileOnly: the host application already brings logback. Shipping our own copy
            // into somebody else's classpath is how a library breaks its host.
            compileOnly(libs.logback.classic)
        }
        jvmTest.dependencies {
            implementation(libs.logback.classic)
        }
        nativeMain.dependencies {
            implementation(ktorLibs.client.curl)
        }
        nativeTest.dependencies {
            // Positive control for M-26: a SelectorManager is the thing known to occupy a
            // Dispatchers.Default worker. Without showing the harness can detect starvation,
            // "curl looks fine" would be an untested claim about the harness, not about curl.
            implementation(ktorLibs.network)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // A real server on a real port. The component under test swallows its own errors by
            // design, and a fake would verify everything except the one thing that can break
            // silently — metrik lost months to exactly that (research 1.5).
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
        }
    }
}
