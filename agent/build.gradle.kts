plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

// The repository this publishes to is no longer named here. It used to be read from `REPOSILITE_URL`
// with the note that "a public build file is a poor place to publish the location of a private Maven
// repository" — and the address is now in the public source of `io.github.youndie.sborka`, together
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

    // An app's targets (research-clients K6, M-145). Android as a target of its own rather than the
    // jvm variant Gradle would otherwise hand an Android build: that variant drags CIO into the app
    // and has nowhere to hang the lifecycle the agent needs there (K5). The browser is wasmJs, which
    // is what a Compose app on the web compiles to; plain js waits for a consumer.
    androidLibrary {
        namespace = "io.github.youndie.tracy.agent"
        compileSdk = 37
        minSdk = 24
    }
    wasmJs {
        browser()
    }

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
        androidMain.dependencies {
            // OkHttp, the engine an Android app already carries, rather than CIO and its selector.
            implementation(ktorLibs.client.okhttp)
            // ON_STOP of the whole process, the moment an app may be killed without another word (K5).
            implementation("androidx.lifecycle:lifecycle-process:2.11.0")
        }
        wasmJsMain.dependencies {
            // The browser's own fetch. The ingest answers CORS for client keys, so a page on
            // another origin can send to it.
            implementation(ktorLibs.client.js)
            // `document` and `window` for the page's own "you are leaving" events (K5).
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
        }

        commonMain.dependencies {
            api(projects.shared)
            // No `ktor-server-*` here (M-141): an app writes logs too, and an app has no server.
            // The server plugin and the delivery wiring live in `:agent-ktor-server`.
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
        }

        // Tests that open a real socket: a fake ingest to deliver to, a peer to call. A real server
        // on a real port because the component under test swallows its own errors by design, and a
        // fake would verify everything except the one thing that can break silently — metrik lost
        // months to exactly that (research 1.5). A page in a browser cannot listen on a port, so
        // these run everywhere except wasmJs, where the rest of the suite still runs.
        val socketTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(ktorLibs.server.core)
                implementation(ktorLibs.server.cio)
            }
        }
        wasmJsTest.dependencies {
            // The page's own events drive a flush; the send is caught by a mock engine, because a
            // page cannot listen on a port to receive it.
            implementation(ktorLibs.client.mock)
        }
        jvmTest.get().dependsOn(socketTest)
        nativeTest.get().dependsOn(socketTest)
    }
}
