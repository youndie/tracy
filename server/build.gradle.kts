plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
}

// NOT PUBLISHED: the server ships as a container image, not as an artefact, so no `sborka.publish`
// here. Explicit API is off for the same reason — nothing resolves this module as a library, so
// there is no consumer for a spelled-out surface to be spelled out for.
kotlin {
    explicitApi = null
}

kotlin {
    jvm()

    listOf(
        macosArm64(),
        linuxX64(),
        linuxArm64(),
    ).forEach { target ->
        target.binaries.executable {
            entryPoint = "ru.workinprogress.tracy.server.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.resources)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.sqlx4k.sqlite)
            implementation(libs.okio)
            implementation(libs.mcp.server)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            // koin-ktor publishes for linuxx64 and macosarm64 — checked in Central, not assumed.
            // Without it `by inject<T>()` in routes would be JVM-only and the layer could not move.
            implementation(libs.koin.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(ktorLibs.server.testHost)
            implementation(ktorLibs.client.contentNegotiation)
        }
    }
}
