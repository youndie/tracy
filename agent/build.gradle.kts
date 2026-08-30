plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

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

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    // Two engines, because one does not exist everywhere. `ktor-client-curl` publishes no Apple
    // mobile artifact — checked in Central, `ktor-client-curl-iosarm64` is a 404 — so iOS gets
    // Darwin. Desktop native keeps Curl deliberately rather than moving to Darwin as well: the
    // Curl klib carries a static libcurl and libssl and resolves host names itself, both verified
    // rather than assumed (research 1.5), and swapping the desktop engine would re-open questions
    // that are already closed.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val desktopNativeMain by creating { dependsOn(sourceSets.getByName("nativeMain")) }
        val desktopNativeTest by creating { dependsOn(sourceSets.getByName("nativeTest")) }

        listOf("macosArm64", "linuxX64", "linuxArm64").forEach { target ->
            sourceSets.getByName("${target}Main").dependsOn(desktopNativeMain)
            sourceSets.getByName("${target}Test").dependsOn(desktopNativeTest)
        }

        desktopNativeMain.dependencies {
            implementation(ktorLibs.client.curl)
        }
        desktopNativeTest.dependencies {
            // Positive control for M-26: a SelectorManager is the thing known to occupy a
            // Dispatchers.Default worker. Without showing the harness can detect starvation,
            // "curl looks fine" would be an untested claim about the harness, not about curl.
            implementation(ktorLibs.network)
        }

        iosMain.dependencies {
            implementation(ktorLibs.client.darwin)
        }

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
