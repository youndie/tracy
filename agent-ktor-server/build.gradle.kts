plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

// The Ktor server half of the agent: `install(Tracy)` for incoming spans and the trace context, and
// `Application.startTracyDelivery`. It used to be inside `:agent`, which made every writer of logs
// depend on `ktor-server-core` — including an app on a phone or in a browser, which has no server
// (research-clients K1, M-141).
//
// Same package as `:agent`, so a service that already imports `io.github.youndie.tracy.agent.Tracy`
// keeps its imports and only gains a dependency line. Server targets only: nobody runs a Ktor server
// inside an iOS app.
kotlin {
    withSourcesJar()

    jvm()

    macosArm64()
    linuxX64()
    linuxArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(projects.agent)
            api(ktorLibs.server.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // A real server on a real port, and a real client calling it: routing, headers and the
            // status are exactly what a fake would get wrong quietly.
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.client.core)
        }
        jvmTest.dependencies {
            implementation(ktorLibs.client.cio)
            implementation(libs.logback.classic)
        }
        nativeTest.dependencies {
            implementation(ktorLibs.client.curl)
        }
    }
}
